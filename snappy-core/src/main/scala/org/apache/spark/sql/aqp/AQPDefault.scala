package org.apache.spark.sql.aqp

import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.columnar.InMemoryAppendableColumnarTableScan
import org.apache.spark.sql.execution._
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.sources.StoreStrategy
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.DStream

import scala.reflect.ClassTag
import scala.reflect.runtime._
import scala.reflect.runtime.{universe => u}
import org.apache.spark.sql.{execution => sparkexecution}
/**
 * Created by ashahid on 12/11/15.
 */
object AQPDefault extends AQPContext{
  protected[sql] def executePlan(context: SnappyContext, plan: LogicalPlan): QueryExecution =
    new sparkexecution.QueryExecution(context, plan)

  def registerSampleTable(context: SnappyContext, tableName: String, schema: StructType,
                          samplingOptions: Map[String, Any], streamTable: Option[String] = None,
                          jdbcSource: Option[Map[String, String]] = None): SampleDataFrame
  = throw new UnsupportedOperationException("missing aqp jar")

  def registerSampleTableOn[A <: Product](context: SnappyContext,
                                                      tableName: String,
                                                      samplingOptions: Map[String, Any], streamTable: Option[String] = None,
                                                      jdbcSource: Option[Map[String, String]] = None)
                                         (implicit ev: u.TypeTag[A]): DataFrame
  = throw new UnsupportedOperationException("missing aqp jar")

  def registerTopK(context: SnappyContext, tableName: String, streamTableName: String,
                   topkOptions: Map[String, Any], isStreamSummary: Boolean): Unit=
    throw new UnsupportedOperationException("missing aqp jar")


  def queryTopK[T: ClassTag](context: SnappyContext, topKName: String,
                             startTime: String = null, endTime: String = null,
                             k: Int = -1): DataFrame = throw new UnsupportedOperationException("missing aqp jar")

  def queryTopK[T: ClassTag](context: SnappyContext, topK: String,
                             startTime: Long, endTime: Long, k: Int): DataFrame
  = throw new UnsupportedOperationException("missing aqp jar")


  def createTopK(df: DataFrame, context: SnappyContext, ident: String, options: Map[String, Any]): Unit
  = throw new UnsupportedOperationException("missing aqp jar")

  protected[sql] def collectSamples(context: SnappyContext, rows: RDD[Row], aqpTables: Seq[String],
                                    time: Long,
                                    storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK)
  = throw new UnsupportedOperationException("missing aqp jar")


  def saveStream[T: ClassTag](context: SnappyContext, stream: DStream[T],
                              aqpTables: Seq[String],
                              formatter: (RDD[T], StructType) => RDD[Row],
                              schema: StructType,
                              transform: RDD[Row] => RDD[Row] = null)
  = throw new UnsupportedOperationException("missing aqp jar")



  def createSampleDataFrameContract(sqlContext: SnappyContext, df: DataFrame, logicalPlan: LogicalPlan): SampleDataFrameContract
  = throw new UnsupportedOperationException("missing aqp jar")

  def convertToStratifiedSample(options: Map[String, Any], logicalPlan: LogicalPlan): LogicalPlan
  = throw new UnsupportedOperationException("missing aqp jar")

  def getPlanner(context: SnappyContext) : SparkPlanner = new DefaultPlanner(context)

  def getSnappyCacheManager(context: SnappyContext): SnappyCacheManager = new SnappyCacheManager(context)

  def getSQLDialectClassName: String = classOf[SnappyParserDialect].getCanonicalName

  def getSampleTablePopulator : Option[(SQLContext) => Unit] = None

  def getSnappyCatalogue(context: SnappyContext) : SnappyStoreHiveCatalog = new SnappyStoreHiveCatalog(context)
}

class DefaultPlanner(context: SnappyContext) extends execution.SparkPlanner(context) {
 override def strategies: Seq[Strategy] = Seq( SnappyStrategies,
   StreamStrategy(context.aqpContext.getSampleTablePopulator),
   StoreStrategy) ++ super.strategies

  object SnappyStrategies extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {

      case PhysicalOperation(projectList, filters,
      mem: columnar.InMemoryAppendableRelation) =>
        pruneFilterProject(
          projectList,
          filters,
          identity[Seq[Expression]], // All filters still need to be evaluated
          InMemoryAppendableColumnarTableScan(_, filters, mem)) :: Nil

      case _ => Nil
    }

  }
}