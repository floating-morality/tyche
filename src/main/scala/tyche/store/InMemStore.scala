package tyche.store

import cats.data.Chain
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import tyche.domain.{ChatData, MessageTemplate}

class InMemStore[F[_]: {Sync, LoggerFactory}] private (ref: Ref[F, Map[Long, ChatData]])
    extends ItemStore[F],
      TemplateStore[F]:

  private given Logger[F] = LoggerFactory[F].getLogger

  def setItems(chatId: Long, items: Chain[String]): F[Unit] =
    for
      _ <- info"setItems chat=$chatId items=$items"
      _ <- update(chatId)(_.copy(items = items))
    yield ()

  def getItems(chatId: Long): F[Chain[String]] =
    for
      _     <- info"getItems chat=$chatId"
      items <- get(chatId).map(_.items)
    yield items

  def addItem(chatId: Long, item: String): F[Unit] =
    for
      _ <- info"addItem chat=$chatId item=$item"
      _ <- update(chatId)(d => d.copy(items = d.items.append(item)))
    yield ()

  def removeItemAt(chatId: Long, index: Int): F[Unit] =
    for
      _ <- info"removeItemAt chat=$chatId index=$index"
      _ <- update(chatId)(d => d.copy(items = Chain.fromSeq(d.items.toList.patch(index, Nil, 1))))
    yield ()

  def getTemplate(chatId: Long): F[MessageTemplate] =
    get(chatId).map(_.messageTemplate)

  def setTemplate(chatId: Long, template: MessageTemplate): F[Unit] =
    for
      _ <- info"setTemplate chat=$chatId"
      _ <- update(chatId)(_.copy(messageTemplate = template))
    yield ()

  def clear(chatId: Long): F[Unit] =
    for
      _ <- info"clear chat=$chatId"
      _ <- ref.update(_ - chatId)
    yield ()

  private def get(chatId: Long): F[ChatData] =
    ref.get.map(_.getOrElse(chatId, ChatData.Empty))

  private def update(chatId: Long)(f: ChatData => ChatData): F[Unit] =
    ref.update { map =>
      val current = map.getOrElse(chatId, ChatData.Empty)
      map.updated(chatId, f(current))
    }

object InMemStore:
  def empty[F[_]: {Sync, LoggerFactory}]: F[InMemStore[F]] =
    of(Map.empty)

  def of[F[_]: {Sync, LoggerFactory}](initial: Map[Long, ChatData]): F[InMemStore[F]] =
    Ref.of[F, Map[Long, ChatData]](initial).map(new InMemStore(_))
