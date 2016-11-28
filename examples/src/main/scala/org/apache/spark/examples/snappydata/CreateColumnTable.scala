/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.examples.snappydata

import java.io.PrintWriter

import com.typesafe.config.Config
import org.apache.log4j.{Level, Logger}

import org.apache.spark.sql.types.{StringType, DecimalType, IntegerType, StructField, StructType}
import org.apache.spark.sql.{SnappySession, SparkSession, SnappyContext, SnappyJobValid, SnappyJobValidation, SnappySQLJob}

/**
 * An example that shows how to create column tables in SnappyData
 * using SQL or APIs.
 *
 * <p></p>
 * This example can be run either in local mode(in which it will spawn a single
 * node SnappyData system) or can be submitted as a job to an already running
 * SnappyData cluster.
 *
 * To run the example in local mode go to you SnappyData product distribution
 * directory and type following command on the command prompt
 * <pre>
 * bin/run-example snappydata.CreateColumnTable
 * </pre>
 *
 */
object CreateColumnTable extends SnappySQLJob {

  override def runSnappyJob(snc: SnappyContext, jobConfig: Config): Any = {
    val pw = new PrintWriter("CreateColumnTable.out")
    createColumnTableUsingAPI(snc, pw)
    createColumnTableUsingSQL(snc, pw)
    createColumnTableInferredSchema(snc, pw)
    pw.close()
  }

  override def isValidJob(sc: SnappyContext, config: Config): SnappyJobValidation = SnappyJobValid()

  /**
   * Creates a column table using APIs
   */
  def createColumnTableUsingAPI(snc: SnappyContext, pw: PrintWriter): Unit = {
    pw.println()

    pw.println("****Create a column table using API****")
    // create a partitioned row table using SQL
    pw.println()
    pw.println("Creating a column table(CUSTOMER) using API")

    snc.dropTable("CUSTOMER", ifExists = true)

    val tableSchema = StructType(Array(StructField("C_CUSTKEY", IntegerType, false),
      StructField("C_NAME", StringType, false),
      StructField("C_ADDRESS", StringType, false),
      StructField("C_NATIONKEY", IntegerType, false),
      StructField("C_PHONE", StringType, false),
      StructField("C_ACCTBAL", DecimalType(15, 2), false),
      StructField("C_MKTSEGMENT", StringType, false),
      StructField("C_COMMENT", StringType, false)
    ))

    // props1 map specifies the properties for the table to be created
    // "PARTITION_BY" attribute specifies partitioning key for CUSTOMER table(C_CUSTKEY),
    // "BUCKETS" attribute specifies the smallest unit that can be moved around in
    // SnappyStore when the data migrates. Here we configure the table to have 11 buckets
    // For complete list of attributes refer the documentation
    val props1 = Map("PARTITION_BY" -> "C_CUSTKEY", "BUCKETS" -> "11")
    snc.createTable("CUSTOMER", "column", tableSchema, props1)

    // insert some data in it
    pw.println()
    pw.println("Loading data in CUSTOMER table from a text file with delimited columns")
    val customerDF = snc.read.
        format("com.databricks.spark.csv").schema(schema = tableSchema).
        load(s"quickstart/src/resources/customer.csv")
    customerDF.write.insertInto("CUSTOMER")

    pw.println()
    var result = snc.sql("SELECT COUNT(*) FROM CUSTOMER").collect()
    pw.println("Number of records in CUSTOMER table after loading data are " + result(0).get(0))

    pw.println()
    pw.println("Inserting a row using INSERT SQL")
    snc.sql("INSERT INTO CUSTOMER VALUES(20000, 'Customer20000', " +
        "'Chicago, IL', 1, '555-101-782', 3500, 'MKTSEGMENT', '')")

    pw.println()
    result = snc.sql("SELECT COUNT(*) FROM CUSTOMER").collect()
    pw.println("Number of records in CUSTOMER table are " + result(0).get(0))

    pw.println("****Done****")
  }

  /**
   * Creates a column table by executing a SQL statement thru SnappyContext
   *
   * Other way to execute a SQL statement is thru JDBC or ODBC driver. Refer to
   * JDBCExample.scala for more details
   */
  def createColumnTableUsingSQL(snc: SnappyContext, pw: PrintWriter): Unit = {

    pw.println()

    pw.println("****Create a column table using SQL****")
    // create a partitioned row table using SQL
    pw.println()
    pw.println("Creating a column table(CUSTOMER) using SQL")

    snc.sql("DROP TABLE IF EXISTS CUSTOMER")

    // Create the table using SQL command
    // "PARTITION_BY" attribute specifies partitioning key for CUSTOMER table(C_CUSTKEY),
    // "BUCKETS" attribute specifies the smallest unit that
    // can be moved around in SnappyStore when the data migrates. Here we specify
    // the table to have 11 buckets
    // For complete list of table attributes refer the documentation
    snc.sql("CREATE TABLE CUSTOMER ( " +
        "C_CUSTKEY     INTEGER NOT NULL," +
        "C_NAME        VARCHAR(25) NOT NULL," +
        "C_ADDRESS     VARCHAR(40) NOT NULL," +
        "C_NATIONKEY   INTEGER NOT NULL," +
        "C_PHONE       VARCHAR(15) NOT NULL," +
        "C_ACCTBAL     DECIMAL(15,2)   NOT NULL," +
        "C_MKTSEGMENT  VARCHAR(10) NOT NULL," +
        "C_COMMENT     VARCHAR(117) NOT NULL)" +
        "USING COLUMN OPTIONS (PARTITION_BY 'C_CUSTKEY', BUCKETS '11' )")

    // insert some data in it
    pw.println()
    pw.println("Loading data in CUSTOMER table from a text file with delimited columns")
    val tableSchema = snc.table("CUSTOMER").schema
    val customerDF = snc.read.
        format("com.databricks.spark.csv").schema(schema = tableSchema).
        load(s"quickstart/src/resources/customer.csv")
    customerDF.write.insertInto("CUSTOMER")

    pw.println()
    var result = snc.sql("SELECT COUNT(*) FROM CUSTOMER").collect()
    pw.println("Number of records in CUSTOMER table after loading data are " + result(0).get(0))

    pw.println()
    pw.println("Inserting a row using INSERT SQL")
    snc.sql("INSERT INTO CUSTOMER VALUES(20000, 'Customer20000', " +
        "'Chicago, IL', 1, '555-101-782', 3500, 'MKTSEGMENT', '')")

    pw.println()
    result = snc.sql("SELECT COUNT(*) FROM CUSTOMER").collect()
    pw.println("Number of records in CUSTOMER table are " + result(0).get(0))

    pw.println("****Done****")
  }

  /**
   * Creates a column table where schema is inferred from parquet data file
   */
  def createColumnTableInferredSchema(snc: SnappyContext, pw: PrintWriter): Unit = {
    pw.println()

    pw.println("****Create a column table using API where schema is inferred from parquet file****")
    // create a partitioned row table using SQL
    pw.println()
    pw.println("Creating a column table(CUSTOMER) using API ")
    snc.dropTable("CUSTOMER", ifExists = true)

    val customerDF = snc.read.parquet(s"quickstart/src/resources/customer_parquet")

    // props1 map specifies the properties for the table to be created
    // "PARTITION_BY" attribute specifies partitioning key for CUSTOMER table(C_CUSTKEY),
    // "BUCKETS" attribute specifies the smallest unit that can be moved around in
    // SnappyStore when the data migrates. Here we configure the table to have 11 buckets
    // For complete list of attributes refer the documentation
    val props1 = Map("PARTITION_BY" -> "C_CUSTKEY", "BUCKETS" -> "11")
    customerDF.write.format("column").mode("append").options(props1).saveAsTable("CUSTOMER")

    pw.println()
    val result = snc.sql("SELECT COUNT(*) FROM CUSTOMER").collect()
    pw.println("Number of records in CUSTOMER table after loading data are " + result(0).get(0))

    pw.println("****Done****")
  }

  def main(args: Array[String]): Unit = {
    // reducing the log level to minimize the messages on console
    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    println("Creating a SnappySession")
    val spark: SparkSession = SparkSession
        .builder
        .appName("CreateColumnTable")
        .master("local[4]")
        .getOrCreate

    val snSession = new SnappySession(spark.sparkContext, existingSharedState = None)

    val pw = new PrintWriter(System.out, true)
    createColumnTableUsingAPI(snSession.snappyContext, pw)
    createColumnTableUsingSQL(snSession.snappyContext, pw)
    createColumnTableInferredSchema(snSession.snappyContext, pw)
    pw.close()
  }

}