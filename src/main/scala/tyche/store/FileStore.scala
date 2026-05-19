package tyche.store

import cats.data.Chain
import cats.effect.kernel.Sync
import cats.effect.std.Mutex
import cats.effect.syntax.resource.*
import cats.effect.{Async, Resource}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import io.circe.parser.decode
import io.circe.syntax.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import tyche.domain.{ChatData, MessageTemplate}

import java.nio.file.{Files, Path, StandardOpenOption}

class FileStore[F[_]: {Sync, LoggerFactory}](path: Path, mutex: Mutex[F]) extends ItemStore[F], TemplateStore[F]:

  private given Logger[F] = LoggerFactory[F].getLogger

  def getItems(chatId: Long): F[Chain[String]] =
    for
      _          <- info"getItems chat=$chatId"
      storedData <- readAll
    yield storedData.getOrElse(chatId, ChatData.Empty).items

  def getTemplate(chatId: Long): F[MessageTemplate] =
    readAll.map(_.getOrElse(chatId, ChatData.Empty).messageTemplate)

  def setItems(chatId: Long, items: Chain[String]): F[Unit] =
    modify(show"setItems chat=$chatId items=$items")(chatId)(_.copy(items = items))

  def addItem(chatId: Long, item: String): F[Unit] =
    modify(show"addItem chat=$chatId item=$item")(chatId)(d => d.copy(items = d.items.append(item)))

  def removeItemAt(chatId: Long, index: Int): F[Unit] =
    modify(show"removeItemAt chat=$chatId index=$index")(chatId) { d =>
      d.copy(items = Chain.fromSeq(d.items.toList.patch(index, Nil, 1)))
    }

  def setTemplate(chatId: Long, template: MessageTemplate): F[Unit] =
    modify(show"setTemplate chat=$chatId template=$template")(chatId)(_.copy(messageTemplate = template))

  def clear(chatId: Long): F[Unit] =
    mutex.lock.surround {
      for
        _   <- info"clear chat=$chatId"
        cur <- readAll
        _   <- writeAll(cur - chatId)
      yield ()
    }

  private def modify(logMsg: String)(chatId: Long)(f: ChatData => ChatData): F[Unit] =
    mutex.lock.surround {
      for
        _   <- Logger[F].info(logMsg)
        cur <- readAll
        next = cur.updated(chatId, f(cur.getOrElse(chatId, ChatData.Empty)))
        _ <- writeAll(next)
      yield ()
    }

  private def readAll: F[Map[Long, ChatData]] =
    Sync[F].blocking {
      if Files.exists(path) then decode[Map[Long, ChatData]](Files.readString(path)).getOrElse(Map.empty)
      else Map.empty
    }

  private def writeAll(data: Map[Long, ChatData]): F[Unit] =
    Sync[F].blocking {
      Files.writeString(path, data.asJson.spaces2, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }.void

object FileStore:
  def make[F[_]: {Async, LoggerFactory}](path: Path): Resource[F, FileStore[F]] =
    Mutex[F].map(new FileStore(path, _)).toResource
