/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package edp.wormhole.sinks.hbasesink

import edp.wormhole.hbaseconnection._
import edp.wormhole.publicinterface.sinks.{SinkProcessConfig, SinkProcessor}
import edp.wormhole.sinks.SourceMutationType
import edp.wormhole.sinks.utils.SinkDefault._
import edp.wormhole.ums.UmsFieldType._
import edp.wormhole.ums.UmsNamespace
import edp.wormhole.ums.UmsOpType._
import edp.wormhole.ums.UmsSysField._
import edp.wormhole.util.JsonUtils
import edp.wormhole.util.config.ConnectionConfig
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.util.Bytes
import org.apache.log4j.Logger

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class Data2HbaseSink extends SinkProcessor{
  private lazy val logger = Logger.getLogger(this.getClass)
  override def process(sourceNamespace: String,
                       sinkNamespace: String,
                       sinkProcessConfig: SinkProcessConfig,
                       schemaMap: collection.Map[String, (Int, UmsFieldType, Boolean)],
                       tupleList: Seq[Seq[String]],
                       connectionConfig: ConnectionConfig): Unit = {
    HbaseConnection.initHbaseConfig(sinkNamespace,  connectionConfig)
    def rowkey(rowkeyConfig: Seq[RowkeyPatternContent], recordValue: Seq[String]): String = {
      val keydatas = rowkeyConfig.map(rowkey => {

        val rkName = rowkey.fieldContent.toLowerCase
        val rkType = rowkey.patternType
        if (rkType == RowkeyPatternType.DELIMIER.toString) {
          rowkey.fieldContent
        } else {
          if (!schemaMap.contains(rkName)) {
            logger.error("schemaMap does not containing " + rkName)
            throw new Exception("schemaMap does not containing " + rkName)
          }
          recordValue(schemaMap(rkName)._1)

        }
      })
      RowkeyTool.generatePatternKey(keydatas, rowkeyConfig)
    }

    def gerneratePuts(hbaseConfig:HbaseConfig, filterRowkey2idTuples: Seq[(String, Long, Seq[String])]): ListBuffer[Put] = {
      val puts: ListBuffer[Put] = new mutable.ListBuffer[Put]
      for (tuple <- filterRowkey2idTuples) {
        try {
          val umsOpValue: String = if(schemaMap.contains(OP.toString)){
            tuple._3(schemaMap(OP.toString)._1)
          }else ""
          val rowkeyBytes = Bytes.toBytes(tuple._1)
          val put =
            if(hbaseConfig.`mutation_type.get`==SourceMutationType.I_U_D.toString) {
              hbaseConfig.`hbase.version.column` match {
                case Some(columnName) =>
                  //logger.info(s"rowkeyBytes $rowkeyBytes, version ${tuple._2}, columnName $columnName")
                  new Put(rowkeyBytes, tuple._2)
                case None => new Put(rowkeyBytes)
              }
            } else new Put(rowkeyBytes)
          schemaMap.keys.foreach { column =>
            val (index, fieldType, _) = schemaMap(column)
            val valueString = tuple._3(index)
            if (OP.toString != column) {
              if (hbaseConfig.`hbase.valueType.get`) put.addColumn(Bytes.toBytes(hbaseConfig.`hbase.columnFamily.get`), Bytes.toBytes(column), s2hbaseStringValue(fieldType, valueString, column,hbaseConfig.`umsTs.valueType.get`))
              else put.addColumn(Bytes.toBytes(hbaseConfig.`hbase.columnFamily.get`), Bytes.toBytes(column), s2hbaseValue(fieldType, valueString))
            } else {
              if (hbaseConfig.`hbase.valueType.get`)
                put.addColumn(Bytes.toBytes(hbaseConfig.`hbase.columnFamily.get`), HbaseConstants.activeColBytes, if (DELETE.toString == umsOpValue.toLowerCase) HbaseConstants.inactiveString else HbaseConstants.activeString)
              else put.addColumn(Bytes.toBytes(hbaseConfig.`hbase.columnFamily.get`), HbaseConstants.activeColBytes, if (DELETE.toString == umsOpValue.toLowerCase) HbaseConstants.inactiveBytes else HbaseConstants.activeBytes)
            }
          }
          puts += put
        } catch {
          case e: Throwable => logger.error("rowkey:" + tuple._1 + ", tuple:" + tuple._3, e)
        }
      }
      puts
    }

    def gernerateData(hbaseConfig:HbaseConfig, filterRowkey2idTuples: Seq[(String, Long, Seq[String])]): (ListBuffer[Put], ListBuffer[Append], ListBuffer[Increment]) = {
      val puts: ListBuffer[Put] = new mutable.ListBuffer[Put]
      val appends: ListBuffer[Append] = new mutable.ListBuffer[Append]
      val increments: ListBuffer[Increment] = new mutable.ListBuffer[Increment]
      for (tuple <- filterRowkey2idTuples) {
        try {
          val umsOpValue: String = if(schemaMap.contains(OP.toString)){
            tuple._3(schemaMap(OP.toString)._1)
          }else ""
          val rowkeyBytes = Bytes.toBytes(tuple._1)
          val put:Put = new Put(rowkeyBytes)
          val append:Append = new Append(rowkeyBytes)
          val increment:Increment = new Increment(rowkeyBytes)
          var isPut = false
          var isAppend = false
          var isIncr = false
          schemaMap.keys.foreach { column =>
            val (index, fieldType, _) = schemaMap(column)
            val valueString = tuple._3(index)
            val columnConfig = hbaseConfig.`hbase.columns.map`.getOrElse(column, null)
            // 获取hbase.columns配置，未配置默认put操作，使用hbase.default.columnFamily作为列族
            if(columnConfig == null){
              isPut = true
              put.addColumn(Bytes.toBytes(hbaseConfig.`hbase.columnFamily.get`), Bytes.toBytes(column), s2hbaseValue(fieldType, valueString))
            }
            else
              columnConfig.actionType.toString match {
                case "append" => {
                  isAppend = true
                  append.add(Bytes.toBytes(columnConfig.name.split(":")(0)), Bytes.toBytes(column), s2hbaseValue(fieldType, valueString))
                }
                case "incr" => {
                  isIncr = true
                  increment.addColumn(Bytes.toBytes(columnConfig.name.split(":")(0)), Bytes.toBytes(column), valueString.trim.toLong)
                }
                case "put" => {
                  isPut = true
                  put.addColumn(Bytes.toBytes(columnConfig.name.split(":")(0)), Bytes.toBytes(column), s2hbaseValue(fieldType, valueString))
                }
              }
          }
          if(isPut) puts += put
          if(isAppend) appends += append
          if(isIncr) increments += increment
        } catch {
          case e: Throwable => logger.error("rowkey:" + tuple._1 + ", tuple:" + tuple._3, e)
        }
      }
      (puts, appends, increments)
    }

    val namespace = UmsNamespace(sinkNamespace)
    val hbaseConfig = JsonUtils.json2caseClass[HbaseConfig](sinkProcessConfig.specialConfig.get)
    // val hbaseConfig = JsonUtils.json2caseClass[HbaseConfig]("{\n    \"mutation_type\": \"i\",\n    \"hbase.default.columnFamily\": \"C\",\n    \"hbase.saveAsString\": true,\n    \"hbase.rowKey\": \"sub(md5(tbid),8)+'_'+value(tbid)+'_'+value(goods_type)\",\n    \"hbase.columns\": [\n        {\n            \"name\": \"C:tbid\",\n            \"actionType\": \"append\"\n        },\n        {\n            \"name\": \"C:goods_type\"\n        },\n        {\n            \"name\": \"C:action\"\n        },\n        {\n            \"name\": \"N:cnt\",\n            \"actionType\": \"incr\"\n        }\n    ]\n}")
    // val hbaseConfig = JsonUtils.json2caseClass[HbaseConfig]("{\n    \"mutation_type\": \"i\",\n    \"hbase.default.columnFamily\": \"f\",\n    \"hbase.saveAsString\": true,\n    \"hbase.rowKey\": \"hash(id)+'_'+value(id)\",\n    \"hbase.columns\": [\n        {\n            \"name\": \"f:id\",\n            \"actionType\": \"incr\"\n        },\n        {\n            \"name\": \"f:city\",\n            \"actionType\": \"append\"\n        }\n    ]\n}")
    val zk = HbaseConnection.getZookeeperInfo(connectionConfig.connectionUrl)
    val rowkeyConfig: String = hbaseConfig.`hbase.rowKey`

    val patternContentList: mutable.Seq[RowkeyPatternContent] = RowkeyTool.parse(rowkeyConfig)

    // logInfo("before format:" + tupleList.size)
    // 组装rowkey
    val rowkey2IdTuples: Seq[(String, Long, Seq[String])] = tupleList.map(tuple => {
      if(hbaseConfig.`mutation_type.get`==SourceMutationType.I_U_D.toString){
        hbaseConfig.`hbase.version.column` match {
          case Some(columnName) => (rowkey(patternContentList, tuple), tuple(schemaMap(columnName)._1).toLong, tuple)
          case None => (rowkey(patternContentList, tuple), tuple(schemaMap(ID.toString)._1).toLong, tuple)
        }
      }else{
        (rowkey(patternContentList, tuple), 0l, tuple)
      }
    })

    val filterRowkey2idTuples = SourceMutationType.sourceMutationType(hbaseConfig.`mutation_type.get`) match {
      case SourceMutationType.I_U_D =>
        hbaseConfig.`hbase.version.column` match {
          case Some(columnName) =>
            logger.info(s"hbase iud version column $columnName")
            rowkey2IdTuples
          case None =>
            logger.info("hbase iud:")
            logger.info("before select:" + rowkey2IdTuples.size)
            val columnList = List((ID.toString, LONG.toString))
            val rowkey2IdMap: Map[String, Map[String, Any]] = HbaseConnection.getDatasFromHbase(namespace.database + ":" + namespace.table, hbaseConfig.`hbase.columnFamily.get`,hbaseConfig.`hbase.valueType.get`, rowkey2IdTuples.map(_._1), columnList, zk._1, zk._2)
            logger.info("before filter:" + rowkey2IdMap.size)
            if (rowkey2IdMap.nonEmpty) {
              rowkey2IdTuples.filter(row => {
                !rowkey2IdMap.contains(row._1) || (rowkey2IdMap(row._1).contains(ID.toString) && rowkey2IdMap(row._1)(ID.toString).asInstanceOf[Long] < row._2)
              })
            } else rowkey2IdTuples
        }
      case SourceMutationType.INSERT_ONLY =>
        rowkey2IdTuples
    }

    try {
      val result:(ListBuffer[Put], ListBuffer[Append], ListBuffer[Increment]) = gernerateData(hbaseConfig, filterRowkey2idTuples)
      val puts = result._1
      if (puts.nonEmpty) {
        HbaseConnection.dataPut(namespace.database + ":" + namespace.table, puts, zk._1, zk._2)
        puts.clear()
      } else logger.info("there is nothing to insert")

      val appends = result._2
      if (appends.nonEmpty) {
        HbaseConnection.dataAppend(namespace.database + ":" + namespace.table, appends, zk._1, zk._2)
        appends.clear()
      } else logger.info("there is nothing to append")

      val increments = result._3
      if (increments.nonEmpty) {
        HbaseConnection.dataIncrement(namespace.database + ":" + namespace.table, increments, zk._1, zk._2)
        increments.clear()
      } else logger.info("there is nothing to insert")
    } catch {
      case e:Throwable => throw new Exception("HBase wirte error: " + e.toString)
    }

  }
}
