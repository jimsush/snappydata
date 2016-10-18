/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package io.snappydata

import scala.collection.JavaConverters._
import scala.language.implicitConversions

import com.gemstone.gemfire.cache.DataPolicy
import com.gemstone.gemfire.cache.execute.FunctionService
import com.gemstone.gemfire.i18n.LogWriterI18n
import com.gemstone.gemfire.internal.SystemTimer
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext
import com.gemstone.gemfire.internal.cache.{LocalRegion, PartitionedRegion}
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.{GfxdListResultCollector, GfxdMessage}
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer
import com.pivotal.gemfirexd.internal.engine.ui.{SnappyRegionStatsCollectorFunction, SnappyRegionStatsCollectorResult}
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation
import io.snappydata.Constant._

import org.apache.spark.sql.SnappyContext
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.hive.ExternalTableType
import org.apache.spark.{Logging, SparkContext}

object SnappyTableStatsProviderService extends Logging {
  @volatile
  private var tableSizeInfo = Map[String, SnappyRegionStatsCollectorResult]()

  def start(sc: SparkContext): Unit = {
    val delay =
      sc.getConf.getOption("spark.snappy.calcTableSizeInterval")
          .getOrElse(DEFAULT_CALC_TABLE_SIZE_SERVICE_INTERVAL).toString.toLong
    Misc.getGemFireCache.getCCPTimer.schedule(
      new SystemTimer.SystemTimerTask {
        var logger: LogWriterI18n = Misc.getGemFireCache.getLoggerI18n

        override def run2(): Unit = {
          tableSizeInfo = getAggregatedTableStatsOnDemand()
        }

        override def getLoggerI18n: LogWriterI18n = {
          logger
        }
      },
      delay, delay)
  }

  def getTableStatsFromService(fullyQualifiedTableName: String):
  Option[SnappyRegionStatsCollectorResult] = {
    if (tableSizeInfo == null || !tableSizeInfo.contains(fullyQualifiedTableName)) {
      None
    } else tableSizeInfo.get(fullyQualifiedTableName)
  }

  def publishColumnTableRowCountStats(): Unit = {
    def asSerializable[C](c: C) = c.asInstanceOf[C with Serializable]
    val regions = asSerializable(Misc.getGemFireCache.getApplicationRegions.asScala)
    for (region: LocalRegion <- regions) {
      if (region.getDataPolicy == DataPolicy.PARTITION ||
          region.getDataPolicy == DataPolicy.PERSISTENT_PARTITION) {
        val table = Misc.getFullTableNameFromRegionPath(region.getFullPath)
        val pr = region.asInstanceOf[PartitionedRegion]
        val container = pr.getUserAttribute.asInstanceOf[GemFireContainer]
        if (table.startsWith(Constant.INTERNAL_SCHEMA_NAME) &&
            table.endsWith(Constant.SHADOW_TABLE_SUFFIX)) {
          if (container != null) {
            val colPos = container.getTableDescriptor.getColumnDescriptor("NUMROWS").getPosition
            val itr = pr.localEntriesIterator(null.asInstanceOf[InternalRegionFunctionContext],
              true, false, true, null).asInstanceOf[PartitionedRegion#PRLocalScanIterator]
            while (itr.hasNext) {
              pr.getPrStats().incPRNumRowsInCachedBatches(itr.next().asInstanceOf[RowLocation]
                  .getRow(container).getColumn(colPos).getInt)
            }
          }
        }
      }
    }
  }


  def getAggregatedTableStatsOnDemand(): Map[String, SnappyRegionStatsCollectorResult] = {
    val serverStats = getTableStatsFromAllServers
    val aggregatedStats = collection.mutable.Map[String, SnappyRegionStatsCollectorResult]()
    val ss = SnappyContext(SnappyContext.globalSparkContext).sessionState
    val samples = ss.catalog.getDataSourceTables(Seq(ExternalTableType.Sample)).map(_.toString())

    serverStats.foreach(stat => {
      val tableName = stat.getRegionName
      if (!samples.contains(tableName)) {
        val oldRecord = aggregatedStats.get(stat.getRegionName)
        if (oldRecord.isDefined) {
          aggregatedStats.put(stat.getRegionName, oldRecord.get.getCombinedStats(stat))
        } else {
          aggregatedStats.put(stat.getRegionName, stat)
        }
      }
    })
    Utils.immutableMap(aggregatedStats)
  }

  private def getTableStatsFromAllServers: Seq[SnappyRegionStatsCollectorResult] = {
    val result = FunctionService.onMembers(GfxdMessage.getAllDataStores)
        .withCollector(new GfxdListResultCollector())
        .execute(SnappyRegionStatsCollectorFunction.ID).getResult().
        asInstanceOf[java.util.ArrayList[java.util.ArrayList[SnappyRegionStatsCollectorResult]]]
        .asScala
    result.flatMap(_.asScala)
  }

}