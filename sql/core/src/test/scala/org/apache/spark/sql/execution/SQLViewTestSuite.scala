/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.sql.{AnalysisException, QueryTest, Row}
import org.apache.spark.sql.internal.SQLConf._
import org.apache.spark.sql.test.{SharedSparkSession, SQLTestUtils}

/**
 * A base suite contains a set of view related test cases for different kind of views
 * Currently, the test cases in this suite should have same behavior across all kind of views
 * TODO: Combine this with [[SQLViewSuite]]
 */
abstract class SQLViewTestSuite extends QueryTest with SQLTestUtils {
  import testImplicits._

  protected def viewTypeString: String
  protected def formattedViewName(viewName: String): String

  def createView(
      viewName: String,
      sqlText: String,
      columnNames: Seq[String] = Seq.empty,
      replace: Boolean = false): String = {
    val replaceString = if (replace) "OR REPLACE" else ""
    val columnString = if (columnNames.nonEmpty) columnNames.mkString("(", ",", ")") else ""
    sql(s"CREATE $replaceString $viewTypeString $viewName $columnString AS $sqlText")
    formattedViewName(viewName)
  }

  def checkViewOutput(viewName: String, expectedAnswer: Seq[Row]): Unit = {
    checkAnswer(sql(s"SELECT * FROM $viewName"), expectedAnswer)
  }

  test("change SQLConf should not change view behavior - caseSensitiveAnalysis") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView("v1", "SELECT c1 FROM t", Seq("C1"))
      withView(viewName) {
        Seq("true", "false").foreach { flag =>
          withSQLConf(CASE_SENSITIVE.key -> flag) {
            checkViewOutput(viewName, Seq(Row(2), Row(3), Row(1)))
          }
        }
      }
    }
  }

  test("change SQLConf should not change view behavior - orderByOrdinal") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView("v1", "SELECT c1 FROM t ORDER BY 1 ASC, c1 DESC", Seq("c1"))
      withView(viewName) {
        Seq("true", "false").foreach { flag =>
          withSQLConf(ORDER_BY_ORDINAL.key -> flag) {
            checkViewOutput(viewName, Seq(Row(1), Row(2), Row(3)))
          }
        }
      }
    }
  }

  test("change SQLConf should not change view behavior - groupByOrdinal") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView("v1", "SELECT c1, count(c1) FROM t GROUP BY 1", Seq("c1", "count"))
      withView(viewName) {
        Seq("true", "false").foreach { flag =>
          withSQLConf(GROUP_BY_ORDINAL.key -> flag) {
            checkViewOutput(viewName, Seq(Row(1, 1), Row(2, 1), Row(3, 1)))
          }
        }
      }
    }
  }

  test("change SQLConf should not change view behavior - groupByAliases") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView(
        "v1", "SELECT c1 as a, count(c1) FROM t GROUP BY a", Seq("a", "count"))
      withView(viewName) {
        Seq("true", "false").foreach { flag =>
          withSQLConf(GROUP_BY_ALIASES.key -> flag) {
            checkViewOutput(viewName, Seq(Row(1, 1), Row(2, 1), Row(3, 1)))
          }
        }
      }
    }
  }

  test("change SQLConf should not change view behavior - ansiEnabled") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView("v1", "SELECT 1/0", Seq("c1"))
      withView(viewName) {
        Seq("true", "false").foreach { flag =>
          withSQLConf(ANSI_ENABLED.key -> flag) {
            checkViewOutput(viewName, Seq(Row(null)))
          }
        }
      }
    }
  }

  test("change current database should not change view behavior") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView("v1", "SELECT * from t")
      withView(viewName) {
        withTempDatabase { db =>
          sql(s"USE $db")
          Seq(4, 5, 6).toDF("c1").write.format("parquet").saveAsTable("t")
          checkViewOutput(viewName, Seq(Row(2), Row(3), Row(1)))
        }
      }
    }
  }

  test("view should read the new data if table is updated") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView("v1", "SELECT c1 from t", Seq("c1"))
      withView(viewName) {
        Seq(9, 7, 8).toDF("c1").write.mode("overwrite").format("parquet").saveAsTable("t")
        checkViewOutput(viewName, Seq(Row(9), Row(7), Row(8)))
      }
    }
  }

  test("add column for table should not affect view output") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName = createView("v1", "SELECT * from t")
      withView(viewName) {
        sql("ALTER TABLE t ADD COLUMN (c2 INT)")
        checkViewOutput(viewName, Seq(Row(2), Row(3), Row(1)))
      }
    }
  }

  test("check cyclic view reference on CREATE OR REPLACE VIEW") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName1 = createView("v1", "SELECT * from t")
      val viewName2 = createView("v2", s"SELECT * from $viewName1")
      withView(viewName2, viewName1) {
        val e = intercept[AnalysisException] {
          createView("v1", s"SELECT * FROM $viewName2", replace = true)
        }.getMessage
        assert(e.contains("Recursive view"))
      }
    }
  }

  test("check cyclic view reference on ALTER VIEW") {
    withTable("t") {
      Seq(2, 3, 1).toDF("c1").write.format("parquet").saveAsTable("t")
      val viewName1 = createView("v1", "SELECT * from t")
      val viewName2 = createView("v2", s"SELECT * from $viewName1")
      withView(viewName2, viewName1) {
        val e = intercept[AnalysisException] {
          sql(s"ALTER VIEW $viewName1 AS SELECT * FROM $viewName2")
        }.getMessage
        assert(e.contains("Recursive view"))
      }
    }
  }
}

class LocalTempViewTestSuite extends SQLViewTestSuite with SharedSparkSession {
  override protected def viewTypeString: String = "TEMPORARY VIEW"
  override protected def formattedViewName(viewName: String): String = viewName

}

class GlobalTempViewTestSuite extends SQLViewTestSuite with SharedSparkSession {
  override protected def viewTypeString: String = "GLOBAL TEMPORARY VIEW"
  override protected def formattedViewName(viewName: String): String = {
    val globalTempDB = spark.sharedState.globalTempViewManager.database
    s"$globalTempDB.$viewName"
  }
}

class PersistedViewTestSuite extends SQLViewTestSuite with SharedSparkSession {
  override protected def viewTypeString: String = "VIEW"
  override protected def formattedViewName(viewName: String): String = s"default.$viewName"
}
