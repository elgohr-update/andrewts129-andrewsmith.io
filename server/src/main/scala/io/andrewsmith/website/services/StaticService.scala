package io.andrewsmith.website.services

import cats.data.OptionT

import java.util.concurrent.Executors
import cats.effect.{Blocker, ContextShift, IO}
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.util.IOUtils
import org.http4s.{HttpRoutes, MediaType, Request, Response, StaticFile}
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`

import java.io.File

object StaticService {
  private val blockingPool = Executors.newFixedThreadPool(4)
  private val blocker = Blocker.liftExecutorService(blockingPool)

  private val acceptedExtensions = Set("js", "css", "map", "html", "png", "ico", "txt")

  def routes(implicit cs: ContextShift[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "AndrewSmithResume.pdf" => Ok(downloadResume(), `Content-Type`(MediaType.application.pdf))
    case request @ GET -> Root => static("index.html", blocker, request)
    case request @ GET -> Root / file => extractExtension(file) match {
      case Some(ext) => if (acceptedExtensions(ext)) static(file, blocker, request) else NotFound()
      case None => static(s"$file.html", blocker, request)
    }
  }

  private def static(file: String, blocker: Blocker, request: Request[IO])(implicit cs: ContextShift[IO]): IO[Response[IO]] = {
    lazy val fromFile = staticFromFile(file, blocker, request)
    lazy val fromResource = staticFromResource(file, blocker, request)

    fromFile.getOrElseF(fromResource.getOrElseF(NotFound()))
  }

  private def staticFromFile(file: String, blocker: Blocker, request: Request[IO])(implicit cs: ContextShift[IO]): OptionT[IO, Response[IO]] = {
    val fileDir = sys.env.getOrElse("STATIC_FILE_DIR", "./static")
    StaticFile.fromFile(new File(s"$fileDir/$file"), blocker, Some(request))
  }

  private def staticFromResource(file: String, blocker: Blocker, request: Request[IO])(implicit cs: ContextShift[IO]): OptionT[IO, Response[IO]] = {
    StaticFile.fromResource(s"/$file", blocker, Some(request))
  }

  private def extractExtension(file: String): Option[String] = file.lastIndexOf('.') match {
    case -1 => None
    case i => Some(file.substring(i + 1))
  }

  // TODO do I really need AWS here? This is stupid
  private def downloadResume(): Array[Byte] = {
    val client: AmazonS3 = AmazonS3ClientBuilder
      .standard()
      .withRegion("us-east-1")
      .withCredentials(new EnvironmentVariableCredentialsProvider())
      .build()

    val resumeObject: S3Object = client.getObject("andrewsmithresume", "resume_b0.pdf")
    IOUtils.toByteArray(resumeObject.getObjectContent)
  }
}
