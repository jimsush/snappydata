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

import scala.util.Try

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.log4j.{Level, Logger}

import org.apache.spark.sql.{SnappyContext, SnappyJobInvalid, SnappyJobValid, SnappyJobValidation, SnappySQLJob, SnappySession, SparkSession}

/**
 * This is a sample code snippet to work with JSON files and SnappyStore column tables.
 * Run with
 * <pre>
 * bin/run-example snappydata.WorkingWithJson quickstart/src/main/resources
 * </pre>
 * Also you can run this example by submitting as a job.
 * <pre>
 *   cd $SNAPPY_HOME
 *   bin/snappy-job.sh submit
 *   --app-name JsonApp
 *   --class org.apache.spark.examples.snappydata.WorkingWithJson
 *   --app-jar examples/jars/quickstart.jar
 *   --lead [leadHost:port]
 *   --conf json_resource_folder=../../quickstart/src/main/resources
 *
 * Check the status of your job id
 * bin/snappy-job.sh status --lead [leadHost:port] --job-id [job-id]
 */
object WorkingWithJson extends SnappySQLJob {

  private val NPARAMS = 1

  private var jsonFolder: String = ""

  override def isValidJob(sc: SnappyContext, config: Config): SnappyJobValidation ={
    {
      Try(config.getString("json_resource_folder"))
          .map(x => SnappyJobValid())
          .getOrElse(SnappyJobInvalid("No json_resource_folder config param"))
    }
  }

  override def runSnappyJob(sc: SnappyContext, jobConfig: Config): Any = {

    val some_people_path = s"${jobConfig.getString("json_resource_folder")}/some_people.json"
    // Read a JSON file using Spark API
    val people = sc.jsonFile(some_people_path)
    people.printSchema()

    //Drop the table if it exists.
    sc.dropTable("people", ifExists = true)

    // Write the created DataFrame to a column table.
    people.write.format("column").saveAsTable("people")

    // Append more people to the column table
    val more_people_path = s"${jobConfig.getString("json_resource_folder")}/more_people.json"

    //Explicitly passing schema to handle record level field mismatch
    // e.g. some records have "district" field while some do not.
    val morePeople = sc.read.schema(people.schema).json(more_people_path)
    morePeople.write.insertInto("people")

    //print schema of the table
    println("Print Schema of the table\n################")
    println(sc.table("people").schema)
    println

    // Query it like any other table
    val nameAndAddress = sc.sql("SELECT " +
        "name, " +
        "address.city, " +
        "address.state, " +
        "address.district, " +
        "address.lane " +
        "FROM people")

    val builder = new StringBuilder
    nameAndAddress.collect.map(row => {
      builder.append(s"${row(0)} ,")
      builder.append(s"${row(1)} ,")
      builder.append(s"${row(2)} ,")
      builder.append(s"${row(3)} ,")
      builder.append(s"${row(4)} \n")

    })
    builder.toString
  }

  def main(args: Array[String]) {

    parseArgs(args)

    // reducing the log level to minimize the messages on console
    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)

    val spark: SparkSession = SparkSession
        .builder
        .appName("WorkingWithJson")
        .master("local[4]")
        .getOrCreate

    val snSession = new SnappySession(spark.sparkContext, existingSharedState = None)
    val config = ConfigFactory.parseString(s"json_resource_folder=$jsonFolder")
    val results = runSnappyJob(snSession.snappyContext, config)
    println("Printing All People \n################## \n" + results)

    spark.stop()
  }

  private def parseArgs(args: Array[String]): Unit = {
    if (args.length != NPARAMS) {
      printUsage()
      System.exit(1)
    }
    jsonFolder = args(0)
  }

  private def printUsage(): Unit = {
    val usage: String =
        "Usage: WorkingWithJson <jsonFolderPath> \n" +
        "\n" +
        "jsonFolderPath - (string) local folder where some_people.json & more_people.json are located\n"
    println(usage)
  }
}