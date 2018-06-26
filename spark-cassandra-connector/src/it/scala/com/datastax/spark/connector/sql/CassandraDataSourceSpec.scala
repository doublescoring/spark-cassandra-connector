package com.datastax.spark.connector.sql

import scala.concurrent.Future
import org.apache.spark.sql.SaveMode._
import org.apache.spark.sql.cassandra.{AnalyzedPredicates, CassandraPredicateRules, CassandraSourceRelation, TableRef}
import org.apache.spark.sql.sources.{EqualTo, Filter}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.BeforeAndAfterEach
import com.datastax.spark.connector._
import com.datastax.spark.connector.SparkCassandraITFlatSpecBase
import com.datastax.spark.connector.cql.{CassandraConnector, TableDef}
import com.datastax.spark.connector.embedded.YamlTransformations
import com.datastax.spark.connector.util.Logging
import org.apache.spark.SparkConf
import com.datastax.spark.connector.rdd.CassandraTableScanRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.execution.RowDataSourceScanExec
import org.apache.spark.sql.types.{IntegerType, StructField}

class CassandraDataSourceSpec extends SparkCassandraITFlatSpecBase with Logging with BeforeAndAfterEach {
  useCassandraConfig(Seq(YamlTransformations.Default))
  useSparkConf(defaultConf)

  override lazy val conn = CassandraConnector(defaultConf)
  conn.withSessionDo { session =>
    createKeyspace(session)

    awaitAll(
      Future {
        session.execute(s"""CREATE TABLE $ks.test1 (a INT, b INT, c INT, d INT, e INT, f INT, g INT, h INT, PRIMARY KEY ((a, b, c), d , e, f))""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 1, 1, 1, 1, 1, 1, 1)""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 1, 1, 1, 2, 1, 1, 2)""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 1, 1, 2, 1, 1, 2, 1)""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 1, 1, 2, 2, 1, 2, 2)""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 2, 1, 1, 1, 2, 1, 1)""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 2, 1, 1, 2, 2, 1, 2)""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 2, 1, 2, 1, 2, 2, 1)""")
        session.execute(s"""INSERT INTO $ks.test1 (a, b, c, d, e, f, g, h) VALUES (1, 2, 1, 2, 2, 2, 2, 2)""")
      },

      Future {
        session.execute(s"CREATE TABLE $ks.test_rowwriter (a INT PRIMARY KEY, b INT)")
      },

      Future {
        session.execute(s"CREATE TABLE $ks.test_insert (a INT PRIMARY KEY, b INT)")
      },

      Future {
        session.execute(s"CREATE TABLE $ks.test_insert1 (a INT PRIMARY KEY, b INT)")
      },

      Future {
        session.execute(s"CREATE TABLE $ks.test_insert2 (a INT PRIMARY KEY, b INT)")
        session.execute(s"INSERT INTO $ks.test_insert2 (a, b) VALUES (3,4)")
        session.execute(s"INSERT INTO $ks.test_insert2 (a, b) VALUES (5,6)")
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.df_test(
             |  customer_id int,
             |  uri text,
             |  browser text,
             |  epoch bigint,
             |  PRIMARY KEY (customer_id, epoch, uri)
             |)""".stripMargin.replaceAll("\n", " "))
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.df_test2(
             |  customer_id int,
             |  uri text,
             |  browser text,
             |  epoch bigint,
             |  PRIMARY KEY (customer_id, epoch)
             |)""".stripMargin.replaceAll("\n", " "))
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.df_underscore_test(
             |  underscore_field int,
             |  key int,
             |  PRIMARY KEY (key)
             |)""".stripMargin.replaceAll("\n", " "))
      },

      Future {
        session.execute(
          s"""
             |CREATE TABLE $ks.df_camelcase_test(
             |  a int,
             |  b_Ccc int,
             |  ddd int,
             |  e_f int,
             |  g_h_ int,
             |  I_J int,
             |  key int,
             |  PRIMARY KEY (key, b_ccc)
             |)""".stripMargin.replaceAll("\n", " "))

        session.execute(s"INSERT INTO $ks.df_camelcase_test (a, b_Ccc, ddd, e_f, g_h_, I_J, key) " +
          "VALUES (0,1,2,3,4,5,6)")
        session.execute(s"INSERT INTO $ks.df_camelcase_test (a, b_Ccc, ddd, e_f, g_h_, I_J, key) " +
          "VALUES (10,11,12,13,14,15,16)")
      },

      Future {
        session.execute(s"""CREATE TYPE $ks.level_third (level_third_data FROZEN <list<text>>)""")
        session.execute(s"""CREATE TYPE $ks.level_second (level_second_data FROZEN <list<level_third>>)""")
        session.execute(
          s"""
             |CREATE TABLE $ks.level_first (
             |  id text PRIMARY KEY,
             |  level_first_data FROZEN <level_second>
             |)""".stripMargin)
      }
    )
  }

  def pushDown: Boolean = true

  override def beforeAll() {
    createTempTable(ks, "test1", "tmpTable")
  }

  override def afterAll() {
    super.afterAll()
    sparkSession.sql("DROP VIEW tmpTable")
  }

  override def afterEach(): Unit ={
    sc.setLocalProperty(CassandraSourceRelation.AdditionalCassandraPushDownRulesParam.name, null)
  }

  def createTempTable(keyspace: String, table: String, tmpTable: String) = {
    sparkSession.sql(
      s"""
        |CREATE TEMPORARY TABLE $tmpTable
        |USING org.apache.spark.sql.cassandra
        |OPTIONS (
        | table "$table",
        | keyspace "$keyspace",
        | pushdown "$pushDown",
        | confirm.truncate "true")
      """.stripMargin.replaceAll("\n", " "))
  }

  def cassandraTable(tableRef: TableRef) : DataFrame = {
    sparkSession.baseRelationToDataFrame(CassandraSourceRelation(tableRef, sparkSession.sqlContext))
  }

  it should "allow to select all rows" in {
    val result = cassandraTable(TableRef("test1", ks)).select("a").collect()
    result should have length 8
    result.head should have length 1
  }

  it should "allow to register as a temp table" in {
    cassandraTable(TableRef("test1", ks)).createOrReplaceTempView("test1")
    val temp = sparkSession.sql("SELECT * from test1").select("b").collect()
    temp should have length 8
    temp.head should have length 1
    sparkSession.sql("DROP VIEW test1")
  }

  it should "allow to insert data into a cassandra table" in {
    createTempTable(ks, "test_insert", "insertTable")
    sparkSession.sql("SELECT * FROM insertTable").collect() should have length 0

    sparkSession.sql("INSERT OVERWRITE TABLE insertTable SELECT a, b FROM tmpTable")
    sparkSession.sql("SELECT * FROM insertTable").collect() should have length 1
    sparkSession.sql("DROP VIEW insertTable")
  }

  it should "allow to save data to a cassandra table" in {
    sparkSession.sql("SELECT a, b from tmpTable")
      .write
      .format("org.apache.spark.sql.cassandra")
      .mode(ErrorIfExists)
      .options(Map("table" -> "test_insert1", "keyspace" -> ks))
      .save()

    cassandraTable(TableRef("test_insert1", ks)).collect() should have length 1

    val message = intercept[UnsupportedOperationException] {
      sparkSession.sql("SELECT a, b from tmpTable")
        .write
        .format("org.apache.spark.sql.cassandra")
        .mode(ErrorIfExists)
        .options(Map("table" -> "test_insert1", "keyspace" -> ks))
        .save()
    }.getMessage

    assert(
      message.contains("SaveMode is set to ErrorIfExists and Table"),
      "We should complain if attempting to write to a table with data if save mode is ErrorIfExists.'")
  }

  it should "allow to overwrite a cassandra table" in {
    sparkSession.sql("SELECT a, b from tmpTable")
      .write
      .format("org.apache.spark.sql.cassandra")
      .mode(Overwrite)
      .options(Map("table" -> "test_insert2", "keyspace" -> ks, "confirm.truncate" -> "true"))
      .save()
    createTempTable(ks, "test_insert2", "insertTable2")
    sparkSession.sql("SELECT * FROM insertTable2").collect() should have length 1
    sparkSession.sql("DROP VIEW insertTable2")
  }

  // This test is just to make sure at runtime the implicit for RDD[Row] can be found
  it should "implicitly generate a rowWriter from it's RDD form" in {
    sparkSession.sql("SELECT a, b from tmpTable").rdd.saveToCassandra(ks, "test_rowwriter")
  }

  it should "allow to filter a table" in {
    sparkSession.sql("SELECT a, b FROM tmpTable WHERE a=1 and b=2 and c=1 and e=1").collect() should have length 2
  }

  it should "allow to filter a table with a function for a column alias" in {
    sparkSession.sql("SELECT * FROM (SELECT (a + b + c) AS x, d FROM tmpTable) " +
      "AS tmpTable1 WHERE x= 3").collect() should have length 4
  }

  it should "allow to filter a table with alias" in {
    sparkSession.sql("SELECT * FROM (SELECT a AS a1, b AS b1, c AS c1, d AS d1, e AS e1" +
      " FROM tmpTable) AS tmpTable1 WHERE  a1=1 and b1=2 and c1=1 and e1=1 ").collect() should have length 2
  }

  it should "be able to save DF with reversed order columns to a Cassandra table" in {
    val test_df = Test(1400820884, "http://foobar", "Firefox", 123242)

    val ss = sparkSession
    import ss.implicits._
    val df = sc.parallelize(Seq(test_df)).toDF

    df.write
      .format("org.apache.spark.sql.cassandra")
      .mode(Overwrite)
      .options(Map("table" -> "df_test", "keyspace" -> ks, "confirm.truncate" -> "true"))
      .save()
    cassandraTable(TableRef("df_test", ks)).collect() should have length 1
  }

  it should "be able to save DF with partial columns to a Cassandra table" in {
    val test_df = TestPartialColumns(1400820884, "Firefox", 123242)

    val ss = sparkSession
    import ss.implicits._
    val df = sc.parallelize(Seq(test_df)).toDF

    df.write
      .format("org.apache.spark.sql.cassandra")
      .mode(Overwrite)
      .options(Map("table" -> "df_test2", "keyspace" -> ks, "confirm.truncate" -> "true"))
      .save()
    cassandraTable(TableRef("df_test2", ks)).collect() should have length 1
  }

  it should "throws exception during overwriting a table when confirm.truncate is false" in {
    val test_df = TestPartialColumns(1400820884, "Firefox", 123242)
    val ss = sparkSession
    import ss.implicits._

    val df = sc.parallelize(Seq(test_df)).toDF

    val message = intercept[UnsupportedOperationException] {
      df.write
        .format("org.apache.spark.sql.cassandra")
        .mode(Overwrite)
        .options(Map("table" -> "df_test2", "keyspace" -> ks, "confirm.truncate" -> "false"))
        .save()
    }.getMessage

    assert(
      message.contains("You are attempting to use overwrite mode"),
      "Exception should be thrown when  attempting to overwrite a table if confirm.truncate is false")

  }

  it should "apply user custom predicates which erase basic pushdowns" in {
    sc.setLocalProperty(
      CassandraSourceRelation.AdditionalCassandraPushDownRulesParam.name,
      "com.datastax.spark.connector.sql.PushdownNothing")

    val df = sparkSession
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map("keyspace" -> ks, "table" -> "test1"))
      .load().filter("a=1 and b=2 and c=1 and e=1")

    val qp = df.queryExecution.executedPlan.toString
    qp should include ("Filter (") // Should have a Spark Filter Step
  }

  it should "apply user custom predicates in the order they are specified" in {
    sc.setLocalProperty(
      CassandraSourceRelation.AdditionalCassandraPushDownRulesParam.name,
      "com.datastax.spark.connector.sql.PushdownNothing,com.datastax.spark.connector.sql.PushdownEverything,com.datastax.spark.connector.sql.PushdownEqualsOnly")

    val df = sparkSession
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map("keyspace" -> ks, "table" -> "test1"))
      .load().filter("a=1 and b=2 and c=1 and e=1")

    def getSourceRDD(rdd: RDD[_]): RDD[_] = {
      if (rdd.dependencies.nonEmpty)
        getSourceRDD(rdd.dependencies.head.rdd)
      else
        rdd
    }

    val cassandraTableScanRDD = getSourceRDD(
      df.queryExecution
        .executedPlan
        .collectLeaves().head // Get Source
        .asInstanceOf[RowDataSourceScanExec]
        .rdd
    ).asInstanceOf[CassandraTableScanRDD[_]]

    val pushedWhere = cassandraTableScanRDD.where
    val predicates = pushedWhere.predicates.head.split("AND").map(_.trim)
    val values = pushedWhere.values
    val pushedPredicates = predicates.zip(values)
    pushedPredicates should contain allOf(
      ("\"a\" = ?", 1),
      ("\"b\" = ?", 2),
      ("\"c\" = ?", 1),
      ("\"e\" = ?", 1)
    )
  }

  it should "pass through local conf properties" in {
    sc.setLocalProperty(
      CassandraSourceRelation.AdditionalCassandraPushDownRulesParam.name,
      "com.datastax.spark.connector.sql.PushdownUsesConf")

    val df = sparkSession
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map("keyspace" -> ks, "table" -> "test1", PushdownUsesConf.testKey -> "Don't Remove"))
      .load().filter("g=1 and h=1")

    // Will throw an exception if local key is not set
    val qp = df.queryExecution.executedPlan

    val df2 = sparkSession
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map("keyspace" -> ks, "table" -> "test1"))
      .load().filter("g=1 and h=1")


    intercept[IllegalAccessException] {
      df2.explain()
    }
  }

  it should "adjust underscore column names to camelcase when explicitly told to" in {
    val df = sparkSession
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map("keyspace" -> ks, "table" -> "df_camelcase_test", "camelcase" -> "true"))
      .load()

    df.select("a").where("a < 10").first().getInt(0) should be(0)
    df.select("bCcc").where("bCcc = 1").first().getInt(0) should be(1)
    df.select("ddd").where("ddd > 10").first().getInt(0) should be(12)
    df.select("eF").where("eF <= 10").first().getInt(0) should be(3)
    df.select("gH").where("gH != 14").first().getInt(0) should be(4)
    df.select("iJ").where("iJ >= 10").first().getInt(0) should be(15)
    df.select("gH", "iJ").where("iJ >= 10 and gH >= 10").first().getInt(0) should be(14)
  }

  it should "be able load columns that are not underscored when camelcase setting is enabled" in {
    val df = sparkSession
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map("table" -> "test1", "keyspace" -> ks, "camelcase" -> "true"))
      .load()
    df.select("b").where("b < 2").count() should be(4)
  }

  it should "discard camelcase setting when user scheme is provided" in {
    val schema = org.apache.spark.sql.types.StructType(Seq(
      StructField("a", IntegerType),
      StructField("b_ccc", IntegerType)
    ))
    val df = sparkSession
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map("keyspace" -> ks, "table" -> "df_camelcase_test", "camelcase" -> "true"))
      .schema(schema)
      .load()

    df.select("b_ccc").where("b_ccc < 5").first().getInt(0) should be(1)
  }

  case class CamelCaseClass(key: Int, underscoreField: Int)

  it should "allow to save camel cased df fields to underscored columns" in {
    sparkSession.createDataFrame(Seq(
      CamelCaseClass(1, underscoreField = 10)
    )).write
      .format("org.apache.spark.sql.cassandra")
      .mode(Overwrite)
      .options(Map(
        "table" -> "df_underscore_test",
        "keyspace" -> ks,
        "confirm.truncate" -> "true"
      ))
      .save()

    val content = cassandraTable(TableRef("df_underscore_test", ks)).collect()
    content should have length 1
    content.head.getAs[Int]("underscore_field") should be(10)
  }

  case class UnderscoreClass(key: Int, underscore_field: Int)

  it should "allow to save underscored df fields to underscored columns" in {
    sparkSession.createDataFrame(Seq(
      UnderscoreClass(1, underscore_field = 20)
    )).write
      .format("org.apache.spark.sql.cassandra")
      .mode(Overwrite)
      .options(Map(
        "table" -> "df_underscore_test",
        "keyspace" -> ks,
        "confirm.truncate" -> "true"
      ))
      .save()

    val content = cassandraTable(TableRef("df_underscore_test", ks)).collect()
    content should have length 1
    content.head.getAs[Int]("underscore_field") should be(20)
  }

  case class InvalidClass(key: Int, non_existing_field: Int)

  it should "complain with meaningful exception when saving non-existing df fields" in {
    intercept[NoSuchElementException] {
      sparkSession.createDataFrame(Seq(
        InvalidClass(1, non_existing_field = 30)
      )).write
        .format("org.apache.spark.sql.cassandra")
        .mode(Append)
        .options(Map("table" -> "df_underscore_test", "keyspace" -> ks))
        .save()
    }
  }

  it should "write and read case class with nested udt" in {
    val ss = sparkSession
    import ss.implicits._
    import CassandraDataSourceSpec._

    val entity = LevelFirst("42", Some(LevelSecond(Some(Seq(LevelThird(Some(Seq("hello"))))))))

    val df = sc.parallelize(Seq(entity)).toDF
    df
      .write
      .format("org.apache.spark.sql.cassandra")
      .options(Map(
        "keyspace" -> ks,
        "table" -> "main_entity",
        "camelcase" -> "true",
        "spark.cassandra.output.camelcase" -> "true"
      ))
      .save()

    val firstEntity = ss
      .read
      .format("org.apache.spark.sql.cassandra")
      .options(Map(
        "keyspace" -> ks,
        "table" -> "main_entity",
        "camelcase" -> "true",
        "spark.cassandra.input.camelcase" -> "true"
      ))
      .load()
      .as[LevelFirst]
      .head

    firstEntity shouldEqual entity
  }

}

object CassandraDataSourceSpec {
  case class LevelFirst(
    id: String,
    levelFirstData: Option[LevelSecond] = None
  )

  case class LevelSecond(
    levelSecondData: Option[Seq[LevelThird]] = None
  )

  case class LevelThird(
    levelThirdData: Option[Seq[String]] = None
  )
}

case class Test(epoch: Long, uri: String, browser: String, customer_id: Int)

case class TestPartialColumns(epoch: Long, browser: String, customer_id: Int)

object PushdownEverything extends CassandraPredicateRules {
  override def apply(
    predicates: AnalyzedPredicates,
    tableDef: TableDef,
    sparkConf: SparkConf): AnalyzedPredicates = {

    AnalyzedPredicates(predicates.handledByCassandra ++ predicates.handledBySpark, Set.empty)
  }
}

object PushdownNothing extends CassandraPredicateRules {
  override def apply(
    predicates: AnalyzedPredicates,
    tableDef: TableDef,
    sparkConf: SparkConf): AnalyzedPredicates = {

    AnalyzedPredicates(Set.empty, predicates.handledByCassandra ++ predicates.handledBySpark)
  }
}

object PushdownEqualsOnly extends CassandraPredicateRules {
  override def apply(
    predicates: AnalyzedPredicates,
    tableDef: TableDef,
    sparkConf: SparkConf): AnalyzedPredicates = {

    val eqFilters = (predicates.handledByCassandra ++ predicates.handledBySpark).collect {
      case x: EqualTo => x: Filter
    }
    AnalyzedPredicates(
      eqFilters,
      (predicates.handledBySpark ++ predicates.handledByCassandra) -- eqFilters)
  }
}

object PushdownUsesConf extends CassandraPredicateRules {
  val testKey = "testkey"
  val notSet = "notset"
  override def apply(
    predicates: AnalyzedPredicates,
    tableDef: TableDef,
    conf: SparkConf): AnalyzedPredicates = {
      if (conf.contains(testKey)) {
        predicates
      } else {
        throw new IllegalAccessException(s"Conf did not contain $testKey")
      }
  }
}
