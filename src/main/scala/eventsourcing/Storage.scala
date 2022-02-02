package xyz.mibon.remittance
package eventsourcing

import akka.NotUsed
import akka.stream.scaladsl.{FileIO, Framing, Source}
import io.circe.{Decoder, Encoder}
import org.slf4j.LoggerFactory

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.{ExecutionContext, Future}
import io.circe.parser.parse

import scala.util.{Failure, Success}

trait Storage {
  def allEvents[Event: Decoder](persistenceId: PersistenceId): Source[Event, NotUsed]

  def write[Event: Encoder](persistenceId: PersistenceId, event: Event): Future[Unit]
}

object Storage {

  object FS {
    case class Config(baseDir: String)
  }

  class FS(config: FS.Config)(implicit ec: ExecutionContext) extends Storage {

    private val logger = LoggerFactory.getLogger(getClass)

    private val framing = Framing.lengthField(4, 0, 10_000, byteOrder = ByteOrder.LITTLE_ENDIAN)

    override def allEvents[Event](
        persistenceId: PersistenceId
    )(implicit decoder: Decoder[Event]): Source[Event, NotUsed] = {
      val path = filename(persistenceId)

      if (!Files.exists(path)) return Source.empty

      FileIO
        .fromPath(path)
        .via(framing)
        .map { bytes =>
          val result = parse(bytes.drop(4).utf8String).flatMap { json =>
            decoder.decodeJson(json)
          }

          result match {
            case Right(decoded) => decoded
            case Left(err) =>
              throw new RuntimeException(s"error while decoding an event for $persistenceId", err)
          }
        }
        .mapMaterializedValue { future =>
          future.onComplete {
            case Success(_) =>
              logger.debug(s"events for $persistenceId have been read")
            case Failure(exception) =>
              logger.error(s"error while reading events for $persistenceId", exception)
          }
          NotUsed
        }
    }

    override def write[Event](persistenceId: PersistenceId, event: Event)(implicit
        encoder: Encoder[Event]
    ): Future[Unit] = {
      logger.info(s"saving an event for $persistenceId")

      val payload = encoder(event).noSpaces.getBytes
      val bytes   = ByteBuffer.allocate(payload.length + 4)
      bytes.order(ByteOrder.LITTLE_ENDIAN)
      bytes.putInt(payload.length)
      bytes.put(payload)

      Future {
        Files.write(
          filename(persistenceId),
          bytes.array(),
          StandardOpenOption.APPEND,
          StandardOpenOption.CREATE
        )
      }
    }

    private def filename(persistenceId: PersistenceId): Path =
      Path.of(config.baseDir, persistenceId.id)
  }
}
