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

package org.apache.spark.sql.sources

import java.io.File

import com.google.common.io.Files
import org.apache.hadoop.fs.Path

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.{AnalysisException, SaveMode}
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}


class ParquetHadoopFsRelationSuite extends HadoopFsRelationTest {
  override val dataSourceName: String = "parquet"

  import sqlContext._
  import sqlContext.implicits._

  test("save()/load() - partitioned table - simple queries - partition columns in data") {
    withTempDir { file =>
      val basePath = new Path(file.getCanonicalPath)
      val fs = basePath.getFileSystem(SparkHadoopUtil.get.conf)
      val qualifiedBasePath = fs.makeQualified(basePath)

      for (p1 <- 1 to 2; p2 <- Seq("foo", "bar")) {
        val partitionDir = new Path(qualifiedBasePath, s"p1=$p1/p2=$p2")
        sparkContext
          .parallelize(for (i <- 1 to 3) yield (i, s"val_$i", p1))
          .toDF("a", "b", "p1")
          .write.parquet(partitionDir.toString)
      }

      val dataSchemaWithPartition =
        StructType(dataSchema.fields :+ StructField("p1", IntegerType, nullable = true))

      checkQueries(
        read.format(dataSourceName)
          .option("dataSchema", dataSchemaWithPartition.json)
          .load(file.getCanonicalPath))
    }
  }

  test("SPARK-7868: _temporary directories should be ignored") {
    withTempPath { dir =>
      val df = Seq("a", "b", "c").zipWithIndex.toDF()

      df.write
        .format("parquet")
        .save(dir.getCanonicalPath)

      df.write
        .format("parquet")
        .save(s"${dir.getCanonicalPath}/_temporary")

      checkAnswer(read.format("parquet").load(dir.getCanonicalPath), df.collect())
    }
  }

  test("SPARK-8014: Avoid scanning output directory when SaveMode isn't SaveMode.Append") {
    withTempDir { dir =>
      val path = dir.getCanonicalPath
      val df = Seq(1 -> "a").toDF()

      // Creates an arbitrary file.  If this directory gets scanned, ParquetRelation2 will throw
      // since it's not a valid Parquet file.
      val emptyFile = new File(path, "empty")
      Files.createParentDirs(emptyFile)
      Files.touch(emptyFile)

      // This shouldn't throw anything.
      df.write.format("parquet").mode(SaveMode.Ignore).save(path)

      // This should only complain that the destination directory already exists, rather than file
      // "empty" is not a Parquet file.
      assert {
        intercept[AnalysisException] {
          df.write.format("parquet").mode(SaveMode.ErrorIfExists).save(path)
        }.getMessage.contains("already exists")
      }

      // This shouldn't throw anything.
      df.write.format("parquet").mode(SaveMode.Overwrite).save(path)
      checkAnswer(read.format("parquet").load(path), df)
    }
  }

  test("SPARK-8079: Avoid NPE thrown from BaseWriterContainer.abortJob") {
    withTempPath { dir =>
      intercept[AnalysisException] {
        // Parquet doesn't allow field names with spaces.  Here we are intentionally making an
        // exception thrown from the `ParquetRelation2.prepareForWriteJob()` method to trigger
        // the bug.  Please refer to spark-8079 for more details.
        range(1, 10)
          .withColumnRenamed("id", "a b")
          .write
          .format("parquet")
          .save(dir.getCanonicalPath)
      }
    }
  }

  test("SPARK-8604: Parquet data source should write summary file while doing appending") {
    withTempPath { dir =>
      val path = dir.getCanonicalPath
      val df = sqlContext.range(0, 5)
      df.write.mode(SaveMode.Overwrite).parquet(path)

      val summaryPath = new Path(path, "_metadata")
      val commonSummaryPath = new Path(path, "_common_metadata")

      val fs = summaryPath.getFileSystem(configuration)
      fs.delete(summaryPath, true)
      fs.delete(commonSummaryPath, true)

      df.write.mode(SaveMode.Append).parquet(path)
      checkAnswer(sqlContext.read.parquet(path), df.unionAll(df))

      assert(fs.exists(summaryPath))
      assert(fs.exists(commonSummaryPath))
    }
  }
}
