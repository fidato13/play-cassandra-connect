package controllers

import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test._
import java.io.{FileWriter, FileOutputStream, File}

import controllers._
import play.api.libs.Files.TemporaryFile
import play.api.mvc.MultipartFormData.FilePart

import java.io.File
import java.nio.file.attribute.PosixFilePermission._
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.util
import javax.inject._

import akka.stream.IOResult
import akka.stream.scaladsl._
import akka.util.ByteString
import play.api._
import play.api.libs.streams._
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import play.core.parsers.Multipart.FileInfo
import scala.io.Source


import scala.concurrent.Future

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject() extends Controller {

  /**
   * Create an Action to render an HTML page with a welcome message.
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def upload = Action(parse.multipartFormData) { request =>
    request.body.file("filez").map { picture =>
      import java.io.File
      val filename = picture.filename
      val contentType = picture.contentType
      picture.ref.moveTo(new File(s"/tmp/picture/$filename"))
      Ok("File uploaded")
    }.getOrElse {
      Redirect(routes.HomeController.index).flashing(
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



  def customUpload = Action(parse.multipartFormData(handleFilePartAsFile)) { request =>
    val fileOption = request.body.file("filez")/*.map {
      case FilePart(key, filename, contentType, file) => {
        val fileLines = Source.fromFile(file).getLines.toList
        fileLines.foreach {x => println(s"the line => $x")}

        file //.toPath
      }
    }*/

    Ok(s"File uploaded: ${fileOption.get}")
  }




}
