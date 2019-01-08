package fr.aphp.eds.spark.postgres

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.sql._
import java.util.Properties
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.jdbc.{JdbcDialect, JdbcDialects, JdbcType}
import org.apache.spark.sql.types._
import org.postgresql.copy.{CopyManager,PGCopyInputStream}
import org.postgresql.core.BaseConnection;
import scala.util.control.Breaks._
import org.apache.spark.sql.SparkSession
import java.io.{BufferedInputStream, BufferedOutputStream, FileInputStream, FileOutputStream}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import java.util.UUID.randomUUID
import scala.reflect.io.Directory


object PGUtil extends java.io.Serializable {

   private def dbPassword(hostname:String, port:String, database:String, username:String ):String = {
    // Usage: val thatPassWord = dbPassword(hostname,port,database,username)
    // .pgpass file format, hostname:port:database:username:password
    val passwdFile = new java.io.File(scala.sys.env("HOME"), ".pgpass")
    var passwd = ""
    val fileSrc = scala.io.Source.fromFile(passwdFile)
    fileSrc.getLines.foreach{line =>
      val connCfg = line.split(":")
      if (hostname == connCfg(0)
        && port == connCfg(1)
        && database == connCfg(2)
        && username == connCfg(3)
      ) { 
        passwd = connCfg(4)
      }
    }
    fileSrc.close
    passwd
  }

  def passwordFromConn(url:String):String = {
    val pattern = "jdbc:postgresql://(.*):(\\d+)/(\\w+)[?]user=(\\w+).*".r
    val pattern(host, port, database, username) = url
    dbPassword(host,port,database,username)
  }

  def connOpen(url:String):Connection = {
    val prop = new Properties()
    prop.put("password", passwordFromConn(url))
    val dbc: Connection = DriverManager.getConnection(url, prop)
    dbc
  }

  private def getSchemaTable(spark:SparkSession, url:String, table:String):StructType={
    val query = s""" select * from $table """ 
    getSchemaQuery(spark, url, query)
  }

  private def getSchemaQuery(spark:SparkSession, url:String, query:String):StructType={
    val queryStr = s"""($query LIMIT 0) as tmp"""
    spark.read.format("jdbc")
      .option("url",url)
      .option("password",passwordFromConn(url))
      .option("driver","org.postgresql.Driver")
      .option("dbtable", queryStr)
      .load.schema
  }

  def tableTruncate(conn:Connection, table:String):Unit ={
    val st: PreparedStatement = conn.prepareStatement(s"TRUNCATE TABLE $table")
    st.executeUpdate()
  }

  def tableDrop(conn:Connection, table:String):Unit ={
    val st: PreparedStatement = conn.prepareStatement(s"DROP TABLE IF EXISTS $table")
    st.executeUpdate()
  }

  def tableCreate(conn:Connection, table:String, schema:StructType, isUnlogged:Boolean):Unit ={
    val unlogged = if(isUnlogged){"UNLOGGED"}else{""}
    val fields = ""
    val queryCreate = s"""CREATE TABLE $unlogged ($fields)"""
    val st: PreparedStatement = conn.prepareStatement(queryCreate)
    st.executeUpdate()
  }

  def tableCopy(conn:Connection, tableSrc:String, tableTarg:String, isUnlogged:Boolean = true):Unit ={
    val unlogged = if(isUnlogged){"UNLOGGED"}else{""}
    val queryCreate = s"""CREATE $unlogged TABLE $tableTarg (LIKE $tableSrc)"""
    val st: PreparedStatement = conn.prepareStatement(queryCreate)
    st.executeUpdate()
  }

  def getMinMaxForColumn(spark:SparkSession, url:String, query:String, partitionColumn:String):Tuple2[Long,Long]={
    val min_max_query = s"(SELECT cast(min($partitionColumn) as bigint), cast(max($partitionColumn) as bigint) FROM $query) AS tmp1"
    val row  = spark.read.format("jdbc")
	.option("url",url)
	.option("driver","org.postgresql.Driver")
	.option("dbtable",min_max_query)
        .option("password",passwordFromConn(url))
        .load.first
    val lowerBound = row.getLong(0)
    val upperBound = row.getLong(1)
    (lowerBound, upperBound)
  }

  def getPartitions(spark:SparkSession, lowerBound:Long, upperBound:Long, numPartitions:Int):Dataset[Row]={
    val length = BigInt(1) + upperBound - lowerBound
    val partitions = (0 until numPartitions).map { i =>
      val start = lowerBound + ((i * length) / numPartitions)
      val end = lowerBound + (((i + 1) * length) / numPartitions) - 1
      Row(start.toLong, end.toLong)
    }.toList
    val schema = new StructType().add(StructField("lowerBound", LongType, true)).add(StructField("upperBound",LongType,true))
    val rdd = spark.sparkContext.parallelize(partitions)
    spark.createDataFrame(rdd, schema)
  }

  def inputQueryDf(spark:SparkSession, url:String, query:String,partitionColumn:String,numPartitions:Int):Dataset[Row]={
    // get min and max for partitioning
    val queryStr = s"($query) as tmp"
    val (lowerBound, upperBound): Tuple2[Long,Long] = getMinMaxForColumn(spark, url, queryStr, partitionColumn)
    // get the partitionned dataset from multiple jdbc stmts
    spark.read.format("jdbc").option("url",url)
	.option("dbtable",queryStr)
	.option("driver","org.postgresql.Driver")
	.option("partitionColumn",partitionColumn).option("lowerBound",lowerBound).option("upperBound",upperBound).option("numPartitions",numPartitions).option("fetchsize",50000).option("password",passwordFromConn(url)).load
    }
  
  def formatRow(lst:Seq[org.apache.spark.sql.Row]):String={
  val str = StringBuilder.newBuilder
  var count = 0
  for(input <- lst){
          for (i <- 0 to input.size -1){
          if(i != 0){str.append(",")}
            str.append(Option(input.get(i)) match {
              case Some(d: String) if d.nonEmpty => if( d.contains(",") || d.contains("\n") ){"\"" + d.replace("\"","\"\"") + "\""}else{d}
              case Some(None) => ""
              case None => ""
              case _ => input.get(i).toString
            })
          }
   count += 1
   if(count < lst.size){str.append("\n")}
   }
  str.toString
  }

  def outputBulk(url:String, table:String, df:Dataset[Row], batchsize:Int = 50000) = {
    val dialect = JdbcDialects.get(url)
    val copyColumns =  df.schema.fields.map(x => dialect.quoteIdentifier(x.name)).mkString(",")

    val tmp = df.rdd.mapPartitions(
      batch => 
      {
      val conn = connOpen(url)
      batch.grouped(batchsize).foreach{
         session => 
         {
           val str = formatRow(session)
           val targetStream = new ByteArrayInputStream(str.getBytes());
           val copyManager: CopyManager = new CopyManager(conn.asInstanceOf[BaseConnection] );
           copyManager.copyIn(s"""COPY $table ($copyColumns) FROM STDIN WITH CSV DELIMITER ',' ESCAPE '"' QUOTE '"' """, targetStream  );
         }
      }
    conn.close()
    batch 
    }).take(1)
  }
  
  def inputQueryPartBulkCsv(spark:SparkSession, fsConf:String, url:String, query:String, path:String, numPartitions:Int, partitionColumn:String) = {
    val queryStr = s"($query) as tmp"
    val (lowerBound, upperBound) = getMinMaxForColumn(spark, url, queryStr, partitionColumn)
    val df = getPartitions(spark, lowerBound, upperBound, numPartitions)

    val tmp = df.repartition(200).rdd.mapPartitions(
      x => { 
      val conn = connOpen(url)
        x.foreach{ 
          s => {
          val queryPart = s"SELECT * FROM $queryStr WHERE $partitionColumn between ${s.get(0)} AND ${s.get(1)}"
          inputQueryBulkCsv(fsConf, conn, queryPart, path)
          }
        }
      conn.close()
      x
      }).take(1)
  } 

  def inputQueryBulkCsv(fsConf:String, conn:Connection, query:String, path:String) = {
    val sqlStr = s""" COPY ($query) TO STDOUT  WITH DELIMITER AS ',' NULL AS '' CSV  ENCODING 'UTF-8' QUOTE '"' ESCAPE '"' """
    val copyInputStream: PGCopyInputStream  = new PGCopyInputStream(conn.asInstanceOf[BaseConnection],sqlStr)

    val conf = new Configuration()
    conf.set("fs.defaultFS", fsConf)
    val fs= FileSystem.get(conf)
    val output = fs.create(new Path(path , "part-" + randomUUID.toString))

    var flag = true
    while(flag){
      val t = copyInputStream.read()
      if(t > 0){ 
      output.write(t);
      output.write(copyInputStream.readFromCopy());
      }else{
      output.close()
      flag = false}
    }
  }

  def inputQueryBulkDf(spark:SparkSession, url:String, query:String, path:String, isMultiline:Boolean = false, numPartitions:Int=1, partitionColumn:String=""):Dataset[Row]={
    val defaultFSConf = spark.sessionState.newHadoopConf().get("fs.defaultFS")
    val fsConf = if( path.startsWith("file:") ){ "file:///" }else{ defaultFSConf }

    val conf = new Configuration()
    conf.set("fs.defaultFS", fsConf)
    val fs= FileSystem.get(conf)
    fs.delete(new Path(path), true) // delete file, true for recursive 

    val schemaQuery = getSchemaQuery(spark, url, query)
    if(numPartitions == 1){
    val conn = connOpen(url)
      inputQueryBulkCsv(fsConf, conn, query, path)
    conn.close
    }else{
      inputQueryPartBulkCsv(spark, fsConf, url, query, path, numPartitions, partitionColumn)
    }
    spark.read.format("csv")
      .schema(schemaQuery)
      .option("multiline",isMultiline)
      .option("delimiter",",")
      .option("header",false)
      .option("quote","\"")
      .option("escape","\"")
      .option("timestampFormat", "yyyy-MM-dd HH:mm:ss")
      .option("dateFormat", "yyyy-MM-dd")
      .option("mode","FAILFAST")
      .load(path)
  }

  def outputBulkDfScd1(url:String, table:String, key:String, df:Dataset[Row], batchsize:Int = 50000, excludeColumns:List[String] = Nil):Unit ={
    val tableTmp = table + "_tmp"
    val conn = connOpen(url)
    tableDrop(conn, tableTmp)
    tableCopy(conn, table, tableTmp)
    outputBulk(url, tableTmp, df, batchsize)
    scd1(conn, table, tableTmp, key, df.schema, excludeColumns)
    tableDrop(conn, tableTmp)
  }

  def scd1(conn:Connection, table:String, tableTarg:String, key:String, rddSchema:StructType, excludeColumns:List[String] = Nil):Unit ={
    val updSet =  rddSchema.fields.filter(x => !key.equals(x.name)).filter(x => !excludeColumns.contains(x.name)).map(x => s"${x.name} = tmp.${x.name}").mkString(",")
    val updIsDistinct =  rddSchema.fields.filter(x => !key.equals(x.name)).filter(x => !excludeColumns.contains(x.name)).map(x => s"tmp.${x.name} IS DISTINCT FROM tmp.${x.name}").mkString(" OR ")
    val upd = s"""
    UPDATE $table as targ
    SET $updSet
    FROM $tableTarg as tmp
    WHERE TRUE
    AND targ.$key = tmp.$key
    AND ($updIsDistinct)
    """
    conn.prepareStatement(upd).executeUpdate()

    val insSet =  rddSchema.fields.filter(x => !excludeColumns.contains(x.name)).map(x => s"${x.name}").mkString(",")
    val insSetTarg =  rddSchema.fields.filter(x => !excludeColumns.contains(x.name)).map(x => s"tmp.${x.name}").mkString(",")
    val ins = s"""
    INSERT INTO $table ($insSet)
    SELECT $insSetTarg
    FROM $tableTarg as tmp
    LEFT JOIN $table as targ USING ($key)
    WHERE TRUE
    AND targ.$key IS NULL
    """
    conn.prepareStatement(ins).executeUpdate()

  }

  def outputBulkDfScd2(url:String, table:String, key:String, dateBegin:String, dateEnd:String, df:Dataset[Row], batchsize:Int = 50000, excludeColumns:List[String] = Nil):Unit ={
    val tableTmp = table + "_tmp"
    val conn = connOpen(url)
    tableDrop(conn, tableTmp)
    tableCopy(conn, table, tableTmp)
    outputBulk(url, tableTmp, df, batchsize)
    scd2(conn, table, tableTmp, key, dateBegin, dateEnd, df.schema, excludeColumns)
    tableDrop(conn, tableTmp)
  }

  def scd2(conn:Connection, table:String, tableTmp:String, key:String, dateBegin:String, dateEnd:String, rddSchema:StructType, excludeColumns:List[String] = Nil):Unit ={
    val insCols =    rddSchema.fields.filter(x => x.name!=dateBegin).filter(x => !excludeColumns.contains(x.name)).map(x => s"${x.name}").mkString(",") + "," + dateBegin
    val insColsTmp = rddSchema.fields.filter(x => x.name!=dateBegin).filter(x => !excludeColumns.contains(x.name)).map(x => s"tmp.${x.name}").mkString(",") + ", now()"

    // update  where key AND  is distinct from
    // insert  where key AND  is distinct from
    val oldRows = s"""
    WITH
    upd AS (
    UPDATE $table c
    SET $dateEnd = now()
    FROM $tableTmp tmp
    WHERE TRUE
    AND tmp.$key  = c.$key 
    AND c.$dateEnd IS null
    AND tmp.concept_code IS DISTINCT FROM c.concept_code
    RETURNING c.$key
    )
    INSERT INTO $table ($insCols)
    SELECT $insColsTmp 
    FROM $tableTmp tmp
    JOIN upd USING ($key)
    """
    conn.prepareStatement(oldRows).executeUpdate()

    // insert  where key AND left join null
    val newRows = s"""
    INSERT INTO $table ($insCols)
    SELECT $insColsTmp 
    FROM $tableTmp tmp
    LEFT JOIN $table c USING ($key)
    WHERE c.$key IS null;
    """
    conn.prepareStatement(newRows).executeUpdate()

  }

}
