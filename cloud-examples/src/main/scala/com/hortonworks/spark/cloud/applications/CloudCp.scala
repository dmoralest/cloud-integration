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

package com.hortonworks.spark.cloud.applications

import com.hortonworks.spark.cloud._
import com.hortonworks.spark.cloud.s3.S3AConstantsAndKeys
import com.hortonworks.spark.cloud.utils.ConfigSerDeser
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._

import org.apache.spark.SparkConf
import org.apache.spark.hortonworks.ParallelizedWithLocalityRDD
import org.apache.spark.sql.SparkSession

/**
 * Minimal Implementation of DistCP within Spark.
 *
 * 1. Uses `listFiles(recursive=true)` call for fast tree listing
 * of object store sources.
 * 1. Shuffles source list for better randomness of execution, hence spreading
 * load across shards in the store.
 * 1. Schedules each upload individually. This is *very* inefficient
 * for small and empty files.
 * 1. Uses source locality for work scheduling
 *
 *
 * == Possible Improvements ==
 *
 * - Schedule largest objects first so they don't create a long-tail
 * - file:// source performance by switch to copyFromLocalFile() to get
 *   benefit of any FS-specific speedup (significant speedup seen in "cloudup").
 * - Handling failures by retrying
 * - Batch up small files so scheduling overhead is less. Locality Needed?
 * - Batch up 0 byte files into larger batches with no locality needed.
 * - Collecting FS statistics from each thread
 *
 * I know distcp does a lot with throttling, but that's  quite hard to do here.
 */
class CloudCp extends ObjectStoreExample {

  override protected def usageArgs(): String = {
    "<source> <dest>"
  }

  /**
   * Action to execute.
   *
   * @param sparkConf configuration to use
   * @param args argument array
   * @return an exit code
   */
  override def action(
      sparkConf: SparkConf,
      args: Array[String]): Int = {

    def argPath(index: Int): Option[String] = {
      if (args.length > index) {
        Some(args(index))
      } else {
        None
      }
    }

    args.foreach(a => println(a))

    if (args.length != 2) {
      return usage()
    }

    val source = CloudTestKeys.S3A_CSV_PATH_DEFAULT
    val srcPath = new Path(args(0))
    val destPath = new Path(args(1))

    sparkConf.set("spark.default.parallelism", "4")
    applyObjectStoreConfigurationOptions(sparkConf, false)
    hconf(sparkConf, S3AConstantsAndKeys.FAST_UPLOAD, "true")
    val spark = SparkSession
      .builder
      .appName("CloudCp")
      .config(sparkConf)
      .getOrCreate()

    try {
      val sc = spark.sparkContext
      val sql = sc

      val contextConf = sc.hadoopConfiguration
      val srcFS = srcPath.getFileSystem(contextConf)
      val destConf = new Configuration(contextConf)
      // load this FS instance into memory with random
      val destFS = destPath.getFileSystem(destConf)
      logInfo(s"Listing $srcPath for upload to $destPath")

      val srcStr = srcPath.toUri.toString;
      def stripSource(p: Path): String = {
        p.toUri.toString.substring(srcStr.length)
      }


      rm(destFS, destPath)

      // flat list all files and convert to a list of copy operations
      // including block locations
      val copyList = listFiles(srcFS, srcPath, true).map {
        status =>
          val finalDest = new Path(destPath, stripSource(status.getPath))
          new CopySource(status, finalDest)
      }

      val shuffledCopyList = scala.util.Random.shuffle(copyList).toArray
      val copyCount = shuffledCopyList.length
      // parallelize with each line unique. Inefficient for very small files
      // which we may want to batch up more
      val marshalledSrcConfig = new ConfigSerDeser(contextConf)

      // parallelise the copy.
      val copyOperation = new ParallelizedWithLocalityRDD(
        sc,
        shuffledCopyList,
        copyCount,
        x => shuffledCopyList(x).locationSeq
      ).map { operation =>
        uploadOneFile(operation, marshalledSrcConfig)
      }

      val (copyResults, duration) = durationOf(copyOperation.collect())
      logInfo(s"Copied $copyResults objects in $duration")
      val totalLen = copyResults.foldLeft(0L)( (c, r) => (c + r.len))
      val bandwithBytesNano: Double = if (duration > 0) (totalLen / duration) else 0
      val bandwidthMB = totalLen / 1e6
      val bandwidthSeconds = bandwidthMB / 1e09
      logInfo(s"Total Bytes uploaded $totalLen; bandwidth $bandwidthSeconds MB/s")

    } finally {
      spark.stop()
    }
    0
  }

  /**
   * Copy the file within the worker process.
   *
   * @param operation
   * @param marshalledSrcConfig
   * @return
   */
  def uploadOneFile(operation: CopySource, marshalledSrcConfig: ConfigSerDeser): CopyResult = {
    val workerConf = marshalledSrcConfig.get()
    val workerSrc = operation.srcPath
    val workerSrcFS = workerSrc.getFileSystem(workerConf)
    val workerDest = operation.destPath
    val workerDestFS = workerDest.getFileSystem(workerConf)
    // do the copy
    val (_, d) = durationOf {
      FileUtil.copy(
        workerSrcFS, workerSrc,
        workerDestFS, workerDest,
        false, true, workerConf)
    }
    CopyResult(operation.src, operation.dest, operation.len, d)
  }

}

/**
 * Hadoop 3.x has serializable Path, but this code strives to work with
 * older versions so marshalles Path as String
 * @param src
 * @param dest
 * @param len
 */
case class CopySource(src: String, dest: String, len: Long,
    locations: Seq[BlockLocation]) extends Serializable {

  private val serialVersionUID = -3560248831387566513L

  def this(status: LocatedFileStatus, dest: Path) = {
    this(status.getPath.toUri.toString, dest.toUri.toString, status.getLen,
      status.getBlockLocations.toList)
  }

  override def toString: String = s"CopySource from $src to $dest (len $len)"

  override def hashCode(): Int = src.hashCode()

  override def equals(obj: scala.Any): Boolean = src.equals(obj.asInstanceOf[CopySource].src)

  def srcPath: Path = new Path(src)
  def destPath: Path = new Path(dest)

  /**
   * Return a list of location sor Nil if there aren't any suitable
   * @return
   */
  def locationSeq: Seq[String] = {
    locations.headOption.map(bl => CloudCp.blockLocationToHost(bl))
      .getOrElse(Nil)
  }
}

case class CopyResult(src: String, dest: String, len: Long, duration: Long) extends Serializable;



object CloudCp {

  def main(args: Array[String]) {
    new CloudCp().run(args)
  }

  /**
   * Maps block locations to host. Strips out localhosts
   * @param blockLocation
   * @return
   */
  def blockLocationToHost(blockLocation: BlockLocation): Seq[String] ={
    val hosts = blockLocation.getHosts
    if (hosts == null) {
      return Nil
    }
    hosts.filterNot( h => h.equals("localhoat"))
  }

}