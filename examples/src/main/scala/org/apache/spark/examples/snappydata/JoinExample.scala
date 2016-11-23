package org.apache.spark.examples.snappydata

import java.io.PrintWriter

import com.typesafe.config.Config
import org.apache.log4j.{Level, Logger}

import org.apache.spark.sql.{SnappySession, SparkSession, SnappyJobValid, SnappyJobValidation, SnappyContext, SnappySQLJob}

object JoinExample extends SnappySQLJob {

  override def runSnappyJob(snc: SnappyContext, jobConfig: Config): Any = {
    val pw = new PrintWriter("JoinExample.out")
    runColocatedJoinQuery(snc, pw)
    pw.close()
  }

  override def isValidJob(sc: SnappyContext, config: Config): SnappyJobValidation = SnappyJobValid()

  def runColocatedJoinQuery(snc: SnappyContext, pw: PrintWriter): Unit = {
    pw.println()

    pw.println("Creating a column table(CUSTOMER)")

    snc.sql("DROP TABLE IF EXISTS CUSTOMER")

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

    snc.sql("INSERT INTO CUSTOMER VALUES(20000, 'Customer20000', " +
        "'Chicago, IL', 1, '555-101-782', 3500, 'MKTSEGMENT', '')")
    snc.sql("INSERT INTO CUSTOMER VALUES(30000, 'Customer30000', " +
        "'Boston, MA', 1, '555-151-678', 4500, 'MKTSEGMENT', '')")
    snc.sql("INSERT INTO CUSTOMER VALUES(40000, 'Customer40000', " +
        "'San Jose, CA', 1, '555-532-345', 5500, 'MKTSEGMENT', '')")

    pw.println()
    pw.println("Creating a ORDERS table colocated with CUSTOMER")
    snc.sql("DROP TABLE IF EXISTS ORDERS")
    snc.sql("CREATE TABLE ORDERS  ( " +
        "O_ORDERKEY       INTEGER NOT NULL," +
        "O_CUSTKEY        INTEGER NOT NULL," +
        "O_ORDERSTATUS    CHAR(1) NOT NULL," +
        "O_TOTALPRICE     DECIMAL(15,2) NOT NULL," +
        "O_ORDERDATE      DATE NOT NULL," +
        "O_ORDERPRIORITY  CHAR(15) NOT NULL," +
        "O_CLERK          CHAR(15) NOT NULL," +
        "O_SHIPPRIORITY   INTEGER NOT NULL," +
        "O_COMMENT        VARCHAR(79) NOT NULL) " +
        "USING COLUMN OPTIONS (PARTITION_BY 'O_ORDERKEY', BUCKETS '11', " +
        "COLOCATE_WITH 'CUSTOMER' )")
    snc.sql("INSERT INTO ORDERS VALUES (1, 20000, 'O', 100.50, '2016-04-04', 'LOW', 'Clerk#001', 3, '')")
    snc.sql("INSERT INTO ORDERS VALUES (2, 20000, 'F', 1000, '2016-04-04', 'HIGH', 'Clerk#002', 1, '')")
    snc.sql("INSERT INTO ORDERS VALUES (3, 30000, 'F', 400, '2016-04-04', 'MEDIUM', 'Clerk#003', 2, '')")
    snc.sql("INSERT INTO ORDERS VALUES (4, 30000, 'O', 500, '2016-04-04', 'LOW', 'Clerk#002', 3, '')")

    pw.println("Selecting orders for all customers")
    val result = snc.sql("SELECT C_CUSTKEY, C_NAME, O_ORDERKEY, O_ORDERSTATUS, O_ORDERDATE, " +
        "O_TOTALPRICE FROM CUSTOMER, ORDERS WHERE C_CUSTKEY = O_CUSTKEY").collect()
    pw.println("CUSTKEY, NAME, ORDERKEY, ORDERSTATUS, ORDERDATE, ORDERDATE")
    pw.println("____________________________________________________________")
    result.foreach(pw.println)

    pw.println("****Done****")
  }

  def main(args: Array[String]): Unit = {
    // reducing the log level to minimize the messages on console
    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    println("Creating a SnappySession")
    val spark: SparkSession = SparkSession
        .builder
        .appName("CreateReplicatedRowTable")
        .master("local[4]")
        .getOrCreate

    val snSession = new SnappySession(spark.sparkContext, existingSharedState = None)

    val pw = new PrintWriter(System.out, true)
    runColocatedJoinQuery(snSession.snappyContext, pw)
    pw.close()
  }

}