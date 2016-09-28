package controllers

import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test._
import java.io.{File, FileOutputStream, FileWriter}

import controllers._
import play.api.libs.Files.TemporaryFile
import play.api.mvc.MultipartFormData.FilePart
import java.io.File
import java.nio.file.attribute.PosixFilePermission._
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.text.SimpleDateFormat
import java.util
import javax.inject._

import akka.NotUsed
import akka.stream.IOResult
import akka.stream.scaladsl._
import akka.util.ByteString
import play.api._
import play.api.libs.iteratee.Enumerator
import play.api.libs.streams._
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.core.parsers.Multipart.FileInfo

import scala.io.Source
import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import org.apache.cassandra.config.Config
import org.apache.cassandra.dht.Murmur3Partitioner
import org.apache.cassandra.exceptions.InvalidRequestException
import org.apache.cassandra.io.sstable.CQLSSTableWriter
import java.lang.Long
import java.math.BigDecimal

import scala.util.{Failure, Success}


/**
 * This controller takes the input file from the user by implementing the custom upload method, parse it
  * and then creates cassandra db (sstablewriter files to be dumped into cassandra using sstableloader utility)
 */
@Singleton
class uploadAndWriteCassandraStream @Inject() extends Controller {

  /**
   * Create an Action to render an HTML page with a welcome message.
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def viewUpload = Action {
    Ok(views.html.upload("Upload file to Cassandra"))
  }

  def upload = Action(parse.multipartFormData) { request =>
    request.body.file("filez").map { picture =>
      import java.io.File
      val filename = picture.filename
      val contentType = picture.contentType
      picture.ref.moveTo(new File(s"/tmp/picture/$filename"))
      Ok("File uploaded")
    }.getOrElse {
      Redirect(routes.uploadAndWriteCassandra.index).flashing(
        "error" -> "Missing file")
    }
  }

  type FilePartHandler[A] = FileInfo => Accumulator[ByteString, FilePart[A]]

  def handleFilePartAsFile: FilePartHandler[File] = {
    case FileInfo(partName, filename, contentType) =>
      val perms = java.util.EnumSet.of(OWNER_READ, OWNER_WRITE)
      val attr = PosixFilePermissions.asFileAttribute(perms)
      val path = Files.createTempFile("multipartBody", "tempFile", attr)
      val file = path.toFile

      println(s"File content try => ${file}")



      val fileSink = FileIO.toFile(file)
      val accumulator = Accumulator(fileSink)
      accumulator.map { case IOResult(count, status) =>
        println(s"Number of times accum gets called ")
        FilePart(partName, filename, contentType, file)
      }(play.api.libs.concurrent.Execution.defaultContext)
  }

  val DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd")

  val cassandraTableWriter: CQLSSTableWriter = {

    /** Default output directory */
    val DEFAULT_OUTPUT_DIR = "/tmp/ruf/"

    /** Keyspace name */
    val KEYSPACE = "simplex"

    /** Table name */
    val TABLE = "historical_prices"

    /**
      * Schema for bulk loading table.
      * It is important not to forget adding keyspace name before table name,
      * otherwise CQLSSTableWriter throws exception.
      */
    val SCHEMA = String.format("CREATE TABLE %s.%s (" +
      "ticker ascii, " +
      "date timestamp, " +
      "open decimal, " +
      "high decimal, " +
      "low decimal, " +
      "close decimal, " +
      "volume bigint, " +
      "adj_close decimal, " +
      "PRIMARY KEY (ticker, date) " +
      ") WITH CLUSTERING ORDER BY (date DESC)", KEYSPACE, TABLE)

    /**
      * INSERT statement to bulk load.
      * It is like prepared statement. You fill in place holder for each data.
      */
    val INSERT_STMT = String.format("INSERT INTO %s.%s (" +
      "ticker, date, open, high, low, close, volume, adj_close" +
      ") VALUES (" +
      "?, ?, ?, ?, ?, ?, ?, ?" +
      ")", KEYSPACE, TABLE)

    // magic!
    Config.setClientMode(true)

    // Create output directory that has keyspace and table name in the path
    val outputDir = new File(DEFAULT_OUTPUT_DIR + File.separator + KEYSPACE + File.separator + TABLE)
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      throw new RuntimeException("Cannot create output directory: " + outputDir)
    }

    // Prepare SSTable writer
    val builder: CQLSSTableWriter.Builder = CQLSSTableWriter.builder()
    // set output directory
    builder.inDirectory(outputDir)
      // set target schema
      .forTable(SCHEMA)
      // set CQL statement to put data
      .using(INSERT_STMT)
      // set partitioner if needed
      // default is Murmur3Partitioner so set if you use different one.
      .withPartitioner(new Murmur3Partitioner())

    builder.build()
  }

  def createSSTableWriterFiles(fileParts: List[File]) = {
    fileParts.foreach { file =>
      //all the lines in the files
      val fileLines = scala.io.Source.fromFile(file).getLines.toList
      fileLines.foreach{perRow =>
        val arrString = perRow.split(",")
        println(s"The array content is => $perRow")
        if(arrString(0) != "Date")
        cassandraTableWriter.addRow(
          "YHOO",
          DATE_FORMAT.parse(arrString(0)),
          new BigDecimal(arrString(1)),
          new BigDecimal(arrString(2)),
          new BigDecimal(arrString(3)),
          new BigDecimal(arrString(4)),
          new Long(Long.parseLong(arrString(5))),
          new BigDecimal(arrString(6)))

      }


    }

    cassandraTableWriter.close()
  }


  def customUpload = Action(parse.multipartFormData(handleFilePartAsFile)) { request =>
    var listFilePart = Stream[File]()
    request.body.file("filez").foreach {
      case FilePart(key, filename, contentType, file) => {
        listFilePart =   listFilePart :+ file
      }
    }

    //convert list to iterable
    val streamFileParts = listFilePart.to[collection.immutable.Iterable]

    /**
      * implicit parameters for akka streams
//      */
    implicit val system = ActorSystem("cassandra-stream-play")
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    //defining source for streams
    val source: akka.stream.scaladsl.Source[File, NotUsed] = akka.stream.scaladsl.Source(streamFileParts)

    val flow1 = Flow[File].mapAsyncUnordered(8) { x =>
      //all the lines in the files
      val fileLines = scala.io.Source.fromFile(x).getLines.toList
      fileLines.foreach{perRow =>
        val arrString = perRow.split(",")
        println(s"The array content is => $perRow")
        if(arrString(0) != "Date")
          cassandraTableWriter.addRow(
            "YHOO",
            DATE_FORMAT.parse(arrString(0)),
            new BigDecimal(arrString(1)),
            new BigDecimal(arrString(2)),
            new BigDecimal(arrString(3)),
            new BigDecimal(arrString(4)),
            new Long(Long.parseLong(arrString(5))),
            new BigDecimal(arrString(6)))

      }
      Future(Some(cassandraTableWriter))

    }

      .collect{case Some(v) => cassandraTableWriter.close();v} // here we can do the transformations, in this case i have just kept it as it is

    val sink = Sink.foreach[CQLSSTableWriter] { x => println("closed successfully") }

    val runnab = source.via(flow1).toMat( sink)(Keep.right)

    val actualRun = runnab.run().onComplete { x =>
      x match {
        case Success((t)) => println("something complete here")
        case Failure(tr) => println("Something went wrong => "+tr)
      }
      system.shutdown()
    }

    //createSSTableWriterFiles(streamFileParts)

    Ok(views.html.index(s"File uploaded..."))
  }




}
