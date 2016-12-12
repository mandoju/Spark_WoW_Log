/* SimpleApp.scala */
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.sql._
import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.joda.time.format.DateTimeFormat
import org.joda.time.Minutes
import org.joda.time.Seconds
import scala.collection.mutable.ListBuffer
import org.apache.spark.mllib.fpm.FPGrowth
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.mllib.fpm.PrefixSpan
import org.apache.spark.sql.functions.{collect_list, collect_set}
import java.io._
import org.jfree.chart._
import org.jfree._
import org.jfree.data.xy._
import org.jfree.data.category.CategoryDataset
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.chart.{ChartFactory,ChartPanel}
import org.jfree.chart.plot.PlotOrientation
import scalax.chart._
import scalax.chart.api._

object SimpleApp {
  
  val conf = new SparkConf().setAppName("Simple Application").setMaster("local")
  val sc = new SparkContext(conf)
    val spark = SparkSession
    .builder()
    .appName("Query")
    .enableHiveSupport()
    .config(conf)
    .getOrCreate()
    
  def toInt(s: String): Int = {
  try {
    s.toInt
  } catch {
    case e: Exception => 0
  }
  }
  
  var hps_list = new ListBuffer[(String,Double)]()
  var dps_list = new ListBuffer[(String,Double)]()

  val hps_csv_name = "/home/jorge/logs/hps.csv"
  val dps_csv_name = "/home/jorge/logs/dps.csv"
  
  val total_hps_csv_name = "/home/jorge/logs/total_hps.csv"
  val total_dps_csv_name = "/home/jorge/logs/total_dps.csv"
  
  
    
  def saveDfToCsv(df: DataFrame, tsvOutput: String,
                sep: String = ",", header: Boolean = true): Unit = {
    val tmpParquetDir = "/home/jorge/logs/TMP"
    df.repartition(1).write.
        format("com.databricks.spark.csv").
        option("header", header.toString).
        option("delimiter", sep).
        save(tmpParquetDir)
    val dir = new File(tmpParquetDir)
    val tmpTsvFile = tmpParquetDir + File.separatorChar + "part-00000"
    println(tsvOutput)
    (new File(tmpTsvFile)).renameTo(new File(tsvOutput))
    dir.listFiles.foreach( f => f.delete )
    dir.delete
  }
  
  def main(args: Array[String]) {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)

    
    val logFile = "/home/jorge/logs/log1.csv" // Should be some file on your system
   
    
    
    val logData = sc.textFile(logFile, 2).cache()
    val df = spark.read.format("csv").option("header", "true").load(logFile)
    
    val numAs = logData.filter(line => line.contains("Gotenkz")).count()
    val numBs = logData.filter(line => line.contains("b")).count()
    
    val logData1 = logData.map(x=>x.split(','))
    
    println("Lines with Gotenkz: %s, Lines with b: %s".format(numAs, numBs))
    //df.show()
    df.createOrReplaceTempView("log")
    
    val logSplitted = spark.sql("SELECT split(c0,' ')[0] as data,split(c0,' ')[1] as horario_full,split(c0,'   ')[1] as event_type,* from log")
    logSplitted.toDF().createOrReplaceTempView("log_splitted")
    val wow_log = spark.sql("SELECT * from log_splitted order by horario_full asc")
    wow_log.createOrReplaceTempView("wow_log_full")
    val sqlEncounterStart = spark.sql("SELECT horario_full,c2 from wow_log_full where c0 like '%ENCOUNTER_START%'")
    val sqlEncounterEnd = spark.sql("SELECT horario_full,c0,c2 from wow_log_full where c0 like '%ENCOUNTER_END%'");
    val encounterStart = sqlEncounterStart.first().get(0)
    val encounterBoss = sqlEncounterStart.first().getString(1)
    //println(encounterStart)
    val encounterEnd = sqlEncounterEnd.first().get(0)
    
    
    val wow_log_part = spark.sql("SELECT (SUBSTR(horario_full,0,LENGTH(horario_full) - 4)) as horario,* from wow_log_full where horario_full > '" + encounterStart +  "' and horario_full < '" +  encounterEnd +  "' order by horario_full asc")
    println("wow_log")
    wow_log_part.createOrReplaceTempView("wow_log")
    wow_log_part.show()
    
    
    var encounter_start_time = DateTimeFormat.forPattern("HH:mm:ss").parseDateTime(encounterStart.toString().dropRight(4));
    var encounter_end_time = DateTimeFormat.forPattern("HH:mm:ss").parseDateTime(encounterEnd.toString().dropRight(4));
    var seconds_between = Seconds.secondsBetween(encounter_start_time, encounter_end_time)
    var fight_minutes = seconds_between.getSeconds / 60
    var fight_seconds = seconds_between.getSeconds % 60
    println("a luta demorou %s minutos e %s segundos".format(fight_minutes,fight_seconds))
     
    val players_healers = spark.sql("SELECT c2,sum(c25) from wow_log where c2 != '" + encounterBoss + "' and c2 like '%-%' and c0 like '%_HEAL' group by c2 ")
    //players_healers.show()
     
    //players.foreach { x => println(x.getString(0)) }
    //players_healers.take(1000).foreach { x => printa_HPS(x.getString(0),seconds_between.getSeconds) }
    
    players_healers.take(1000).foreach { x => adiciona_hps(x.getString(0),x.getDouble(1) / seconds_between.getSeconds) }
    
    var hps_list_sorted = hps_list.sortWith{ (x,y) => x._2 > y._2 }
    hps_list_sorted.foreach(x => println("o hps do %s foi %s".format(x._1,x._2) ))
    val hps_csv = hps_list_sorted.mkString("\n") //.replaceAll("(", "").replaceAll(")", "")
    new PrintWriter(hps_csv_name) { write(hps_csv); close }
    
    println()
    val players_damagers = spark.sql("SELECT c2,sum(c25) from wow_log where c2 != '" + encounterBoss + "' and c2 like '%-%' and c0 like '%_DAMAGE' group by c2")
     
    //players.foreach { x => println(x.getString(0)) }
    players_damagers.take(1000).foreach { x => adiciona_dps(x.getString(0),x.getDouble(1) / seconds_between.getSeconds) }
  
    var dps_list_sorted = dps_list.sortWith{ (x,y) => x._2 > y._2 }
    dps_list_sorted.foreach(x => println("o dps do %s foi %s".format(x._1,x._2) ))
    val dps_csv = hps_list_sorted.mkString("\n") //.replaceAll("(", "").replace(")", "")
    new PrintWriter(dps_csv_name) { write(dps_csv); close }
    
    
    val total_damage = spark.sql("SELECT horario,sum(c25) as dps FROM wow_log where lower(c0) like lower('%_damage%') group by horario order by horario asc")
    val total_heal = spark.sql("SELECT horario,sum(c25) as hps FROM wow_log where lower(c0) like lower('%_heal%') group by horario order by horario asc")
    val total_time = spark.sql("SELECT DISTINCT(horario) as horario_segundo from wow_log group by horario order by horario") 
    //println("mosstrando dano total")
    //total_damage.show()
    //total_damage.toDF().repartition(1).write.csv("/home/jorge/logs/total_damage")
    //total_damage.count()
    //val data = for(i <- 1 to 5) yield(i,i)
    //val xy = total_damage.rdd.map(x => (x.getString(0) , x.getLong(1))).collect()
    //val chart = XYLineChart(xy)
    //chart.show()
    
    
    saveDfToCsv(total_damage.toDF(),total_dps_csv_name)
    saveDfToCsv(total_heal.toDF(),total_hps_csv_name)
    
    val primeiro_segundo = DateTimeFormat.forPattern("HH:mm:ss").parseDateTime(total_damage.first().getString(0)).getSecondOfDay
    
    val data = new DefaultCategoryDataset()
    data.getRowCount()
    total_damage.take(100000000).foreach { x => 
      data.addValue(x.getDouble(1),"Damage", (DateTimeFormat.forPattern("HH:mm:ss").parseDateTime(x.getString(0)).getSecondOfDay).toInt - primeiro_segundo.toInt )
      //println(x.getDouble(1))
      //println(DateTimeFormat.forPattern("HH:mm:ss").parseDateTime(x.getString(0)).getSecondOfDay - primeiro_segundo)
     }
    
    data.getRowCount()
    total_heal.take(100000000).foreach { x => 
      data.addValue(x.getDouble(1),"Heal", (DateTimeFormat.forPattern("HH:mm:ss").parseDateTime(x.getString(0)).getSecondOfDay).toInt - primeiro_segundo.toInt )
      //println(x.getDouble(1))
      //println(DateTimeFormat.forPattern("HH:mm:ss").parseDateTime(x.getString(0)).getSecondOfDay - primeiro_segundo)
     }
    
    val chart = ChartFactory.createLineChart("Informações da raid","Tempo","Dano/Heal",data,PlotOrientation.VERTICAL,true,true,true)
 

    
    
    
    val frame = new ChartFrame(
      "DPS da raid",
      chart
    )
    
    
    val ds_heal = new DefaultCategoryDataset
    players_healers.take(100).foreach { x => ds_heal.addValue(x.getDouble(1) / seconds_between.getSeconds , x.getString(0), "Heal") } 
    
    
    val chart_bar =  BarChart(ds_heal)
    chart_bar.show()
    
    
    val ds_dmg = new DefaultCategoryDataset
    players_damagers.take(100).foreach { x => ds_dmg.addValue(x.getDouble(1) / seconds_between.getSeconds , x.getString(0), "Damage") } 
    
    
    val chart_dmg =  BarChart(ds_dmg)
    chart_dmg.show()
    
    frame.pack()
    frame.setVisible(true)
    
    /*val x = List.range(0, total_damage.count()).toArray.map(_.toDouble)
    val y = total_damage.select("dps").rdd.map(r => r.getDouble(0)).collect()
    //val dataset = new DefaultXYDataset
    //dataset.addSeries("DPS",Array(x,y))
    
    
    
    val frame = new ChartFrame(
      "DPS da raid",
      ChartFactory.createLineChart("Plot", "X Label", "Y Label", dataset,
          org.jfree.chart.plot.PlotOrientation.HORIZONTAL,
          false,false,false)
    )*/
    
    //frame.pack()
    //frame.setVisible(true)
    
    //df.write.format("com.databricks.spark.csv").save("/data/home.csv")
   
    
  }
  
  def printa_HPS(x: String,seconds_between: Integer)
  {
     if( !(x.isEmpty()))
     {

       val heal = spark.sql("SELECT * FROM wow_log where lower(c0) like lower('%_heal%') and lower(c2) like lower(\"%" + x + "%\") order by horario asc")
      
       if(heal.count() > 1)
       {
         //heal.show()
         //sqlDF.foreach { x => println(x) }
         heal.toDF().createOrReplaceTempView("wow_hps")
         val hps = spark.sql("select sum(c25) from wow_hps")
         val heal_final = hps.first().getDouble(0)
      
         
          
         var hps_final = heal_final / seconds_between
       
         
         //println("o hps do %s foi %s".format(x,hps_final))
         var tmp = (x,hps_final)
         hps_list += tmp
       }
      }
  }

  def printa_DPS(x: String,seconds_between: Integer)
  {
     if( !(x.isEmpty()))
     {

       val damage = spark.sql("SELECT * FROM wow_log where lower(c0) like lower('%_damage%') and lower(c2) like lower(\"%" + x + "%\") order by horario asc")
      
       if(damage.count() > 1)
       {
         //heal.show()
         //sqlDF.foreach { x => println(x) }
         damage.toDF().createOrReplaceTempView("wow_dps")
         val dps = spark.sql("select sum(c25) from wow_dps")
         val damage_final = dps.first().getDouble(0)
      
    
          
         var dps_final = damage_final / seconds_between
       
         
         //println("o hps do %s foi %s".format(x,hps_final))
         var tmp = (x,dps_final)
         dps_list += tmp
         

         //val damage_skills = spark.sql("SELECT c10 FROM wow_dps order by horario asc")
         //calcula_rotacao(x,damage_skills)
       }
      }
  }
  
  
  def calcula_rotacao(player: String,data_set: DataFrame)
  {
    
    var skills = new ListBuffer[String]
    
    data_set.toDF().createOrReplaceTempView("damages")
    val skills_data_set = spark.sql("select distinct(c10) from damages")
    skills_data_set.foreach { x => 
                              skills += x.getString(0)         
                            }
   
    
  }
  
  
  def adiciona_hps(player: String, hps: Double)
  {
      var tmp = (player,hps)
      hps_list += tmp
  }
  
  def adiciona_dps(player: String, dps: Double)
  {
      var tmp = (player,dps)
      dps_list += tmp
  }
  

}