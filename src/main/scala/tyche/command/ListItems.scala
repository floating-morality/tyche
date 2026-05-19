package tyche.command

import cats.FlatMap
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import telegramium.bots.*
import telegramium.bots.high.*
import telegramium.bots.high.Methods.sendMessage
import telegramium.bots.high.implicits.*
import tyche.UserShow.auditString
import tyche.store.ItemStore

class ListItems[F[_]: {FlatMap, LoggerFactory}](store: ItemStore[F])(using Api[F]) extends Command[F]:

  val cmd = ListItems.Cmd

  private given Logger[F] = LoggerFactory[F].getLogger

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.contains(cmd) =>
      val chatId = msg.chat.id
      for
        _     <- info"[$cmd] chat=$chatId user=${msg.from.auditString}"
        names <- store.getItems(chatId)
        reply =
          if names.isEmpty then CommandHint.NoItems
          else
            names.toList.zipWithIndex
              .map { case (name, i) => s"${i + 1}. $name" }
              .mkString("\n")
        _ <- sendMessage(chatId = ChatIntId(chatId), text = reply).exec.void
      yield ()

object ListItems:
  val Cmd = "/list_items"
