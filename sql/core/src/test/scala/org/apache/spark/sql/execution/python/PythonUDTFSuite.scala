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

package org.apache.spark.sql.execution.python

import org.apache.spark.api.python.PythonEvalType
import org.apache.spark.sql.{AnalysisException, IntegratedUDFTestUtils, QueryTest, Row}
import org.apache.spark.sql.catalyst.expressions.{Add, Alias, FunctionTableSubqueryArgumentExpression, Literal}
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan, OneRowRelation, Project, Repartition, RepartitionByExpression, Sort, SubqueryAlias}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType

class PythonUDTFSuite extends QueryTest with SharedSparkSession {

  import testImplicits._

  import IntegratedUDFTestUtils._

  private val pythonScript: String =
    """
      |class SimpleUDTF:
      |    def eval(self, a: int, b: int):
      |        yield a, b, a + b
      |        yield a, b, a - b
      |        yield a, b, b - a
      |""".stripMargin

  private val returnType: StructType = StructType.fromDDL("a int, b int, c int")

  private val pythonUDTF: UserDefinedPythonTableFunction =
    createUserDefinedPythonTableFunction("SimpleUDTF", pythonScript, returnType)

  private val arrowPythonUDTF: UserDefinedPythonTableFunction =
    createUserDefinedPythonTableFunction(
      "SimpleUDTF",
      pythonScript,
      returnType,
      evalType = PythonEvalType.SQL_ARROW_TABLE_UDF)

  test("Simple PythonUDTF") {
    assume(shouldTestPythonUDFs)
    val df = pythonUDTF(spark, lit(1), lit(2))
    checkAnswer(df, Seq(Row(1, 2, -1), Row(1, 2, 1), Row(1, 2, 3)))
  }

  test("PythonUDTF with lateral join") {
    assume(shouldTestPythonUDFs)
    withTempView("t") {
      spark.udtf.registerPython("testUDTF", pythonUDTF)
      Seq((0, 1), (1, 2)).toDF("a", "b").createOrReplaceTempView("t")
      checkAnswer(
        sql("SELECT f.* FROM t, LATERAL testUDTF(a, b) f"),
        sql("SELECT * FROM t, LATERAL explode(array(a + b, a - b, b - a)) t(c)"))
    }
  }

  test("PythonUDTF in correlated subquery") {
    assume(shouldTestPythonUDFs)
    withTempView("t") {
      spark.udtf.registerPython("testUDTF", pythonUDTF)
      Seq((0, 1), (1, 2)).toDF("a", "b").createOrReplaceTempView("t")
      checkAnswer(
        sql("SELECT (SELECT sum(f.b) AS r FROM testUDTF(1, 2) f WHERE f.a = t.a) FROM t"),
        Seq(Row(6), Row(null)))
    }
  }

  test("Arrow optimized UDTF") {
    assume(shouldTestPandasUDFs)
    val df = arrowPythonUDTF(spark, lit(1), lit(2))
    checkAnswer(df, Seq(Row(1, 2, -1), Row(1, 2, 1), Row(1, 2, 3)))
  }

  test("arrow optimized UDTF with lateral join") {
    assume(shouldTestPandasUDFs)
    withTempView("t") {
      spark.udtf.registerPython("testUDTF", arrowPythonUDTF)
      Seq((0, 1), (1, 2)).toDF("a", "b").createOrReplaceTempView("t")
      checkAnswer(
        sql("SELECT t.*, f.c FROM t, LATERAL testUDTF(a, b) f"),
        sql("SELECT * FROM t, LATERAL explode(array(a + b, a - b, b - a)) t(c)"))
    }
  }

  test("non-deterministic UDTF should pass check analysis") {
    assume(shouldTestPythonUDFs)
    withSQLConf(SQLConf.OPTIMIZE_ONE_ROW_RELATION_SUBQUERY.key -> "true") {
      spark.udtf.registerPython("testUDTF", pythonUDTF)
      withTempView("t") {
        Seq((0, 1), (1, 2)).toDF("a", "b").createOrReplaceTempView("t")
        val df = sql("SELECT f.* FROM t, LATERAL testUDTF(a, b) f")
        df.queryExecution.assertAnalyzed()
      }
    }
  }

  test("SPARK-44503: Specify PARTITION BY and ORDER BY for TABLE arguments") {
    // Positive tests
    assume(shouldTestPythonUDFs)
    def failure(plan: LogicalPlan): Unit = {
      fail(s"Unexpected plan: $plan")
    }
    sql(
      """
        |SELECT * FROM testUDTF(
        |  TABLE(VALUES (1), (1) AS tab(x))
        |  PARTITION BY X)
        |""".stripMargin).queryExecution.analyzed
      .collectFirst { case r: RepartitionByExpression => r }.get match {
      case RepartitionByExpression(
        _, Project(
          _, SubqueryAlias(
            _, _: LocalRelation)), _, _) =>
      case other =>
        failure(other)
    }
    sql(
      """
        |SELECT * FROM testUDTF(
        |  TABLE(VALUES (1), (1) AS tab(x))
        |  WITH SINGLE PARTITION)
        |""".stripMargin).queryExecution.analyzed
      .collectFirst { case r: Repartition => r }.get match {
      case Repartition(
        1, true, SubqueryAlias(
          _, _: LocalRelation)) =>
      case other =>
        failure(other)
    }
    sql(
      """
        |SELECT * FROM testUDTF(
        |  TABLE(VALUES ('abcd', 2), ('xycd', 4) AS tab(x, y))
        |  PARTITION BY SUBSTR(X, 2) ORDER BY (X, Y))
        |""".stripMargin).queryExecution.analyzed
      .collectFirst { case r: Sort => r }.get match {
      case Sort(
        _, false, RepartitionByExpression(
          _, Project(
            _, SubqueryAlias(
              _, _: LocalRelation)), _, _)) =>
      case other =>
        failure(other)
    }
    sql(
      """
        |SELECT * FROM testUDTF(
        |  TABLE(VALUES ('abcd', 2), ('xycd', 4) AS tab(x, y))
        |  WITH SINGLE PARTITION ORDER BY (X, Y))
        |""".stripMargin).queryExecution.analyzed
      .collectFirst { case r: Sort => r }.get match {
      case Sort(
        _, false, Repartition(
          1, true, SubqueryAlias(
            _, _: LocalRelation))) =>
      case other =>
        failure(other)
    }
    // Negative tests
    withTable("t") {
      sql("create table t(col array<int>) using parquet")
      val query = "select * from explode(table(t))"
      checkError(
        exception = intercept[AnalysisException](sql(query)),
        errorClass = "DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE",
        parameters = Map(
          "sqlExpr" -> "\"explode(outer(__auto_generated_subquery_name_0.c))\"",
          "paramIndex" -> "1",
          "inputSql" -> "\"outer(__auto_generated_subquery_name_0.c)\"",
          "inputType" -> "\"STRUCT<col: ARRAY<INT>>\"",
          "requiredType" -> "(\"ARRAY\" or \"MAP\")"),
        context = ExpectedContext(
          fragment = "explode(table(t))",
          start = 14,
          stop = 30))
    }
  }

  test("SPARK-44503: Compute partition child indexes for various UDTF argument lists") {
    // Each of the following tests calls the PythonUDTF.partitionChildIndexes with a list of
    // expressions and then checks the PARTITION BY child expression indexes that come out.
    val projectList = Seq(
      Alias(Literal(42), "a")(),
      Alias(Literal(43), "b")())
    val projectTwoValues = Project(
      projectList = projectList,
      child = OneRowRelation())
    // There are no UDTF TABLE arguments, so there are no PARTITION BY child expression indexes.
    val partitionChildIndexes = FunctionTableSubqueryArgumentExpression.partitionChildIndexes(_)
    assert(partitionChildIndexes(Seq(
      Literal(41))) ==
      Seq.empty[Int])
    assert(partitionChildIndexes(Seq(
      Literal(41),
      Literal("abc"))) ==
      Seq.empty[Int])
    // The UDTF TABLE argument has no PARTITION BY expressions, so there are no PARTITION BY child
    // expression indexes.
    assert(partitionChildIndexes(Seq(
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues))) ==
      Seq.empty[Int])
    // The UDTF TABLE argument has two PARTITION BY expressions which are equal to the output
    // attributes from the provided relation, in order. Therefore the PARTITION BY child expression
    // indexes are 0 and 1.
    assert(partitionChildIndexes(Seq(
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues,
        partitionByExpressions = projectTwoValues.output))) ==
      Seq(0, 1))
    // The UDTF TABLE argument has one PARTITION BY expression which is equal to the first output
    // attribute from the provided relation. Therefore the PARTITION BY child expression index is 0.
    assert(partitionChildIndexes(Seq(
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues,
        partitionByExpressions = Seq(projectList.head.toAttribute)))) ==
      Seq(0))
    // The UDTF TABLE argument has one PARTITION BY expression which is equal to the second output
    // attribute from the provided relation. Therefore the PARTITION BY child expression index is 1.
    assert(partitionChildIndexes(Seq(
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues,
        partitionByExpressions = Seq(projectList.last.toAttribute)))) ==
      Seq(1))
    // The UDTF has one scalar argument, then one TABLE argument, then another scalar argument. The
    // TABLE argument has two PARTITION BY expressions which are equal to the output attributes from
    // the provided relation, in order. Therefore the PARTITION BY child expression indexes are 1
    // and 2, because they begin at an offset of 1 from the zero-based start of the list of values
    // provided to the UDTF 'eval' method.
    assert(partitionChildIndexes(Seq(
      Literal(41),
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues,
        partitionByExpressions = projectTwoValues.output),
      Literal("abc"))) ==
      Seq(1, 2))
    // Same as above, but the PARTITION BY expressions are new expressions which must be projected
    // after all the attributes from the relation provided to the UDTF TABLE argument. Therefore the
    // PARTITION BY child indexes are 3 and 4 because they begin at an offset of 3 from the
    // zero-based start of the list of values provided to the UDTF 'eval' method.
    assert(partitionChildIndexes(Seq(
      Literal(41),
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues,
        partitionByExpressions = Seq(Literal(42), Literal(43))),
      Literal("abc"))) ==
      Seq(3, 4))
    // Same as above, but the PARTITION BY list comprises just one addition expression.
    assert(partitionChildIndexes(Seq(
      Literal(41),
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues,
        partitionByExpressions = Seq(Add(projectList.head.toAttribute, Literal(1)))),
      Literal("abc"))) ==
      Seq(3))
    // Same as above, but the PARTITION BY list comprises one literal value and one addition
    // expression.
    assert(partitionChildIndexes(Seq(
      Literal(41),
      FunctionTableSubqueryArgumentExpression(
        plan = projectTwoValues,
        partitionByExpressions = Seq(Literal(42), Add(projectList.head.toAttribute, Literal(1)))),
      Literal("abc"))) ==
      Seq(3, 4))
  }
}
