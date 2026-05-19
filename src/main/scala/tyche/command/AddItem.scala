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

class AddItem[F[_]: {FlatMap, LoggerFactory}](store: ItemStore[F])(using Api[F]) extends Command[F]:

  val cmd = AddItem.Cmd

  private given Logger[F] = LoggerFactory[F].getLogger

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.exists(Command.matches(_, cmd)) =>
      val chatId = msg.chat.id
      val name   = msg.text.map(_.stripPrefix(cmd).trim).filter(_.nonEmpty)
      for
        _ <- info"[$cmd] chat=$chatId user=${msg.from.auditString} name=${name.getOrElse("<missing>")}"
        _ <- name match
          case None =>
            sendMessage(ChatIntId(chatId), s"Usage: $cmd <item>").exec.void
          case Some(newItem) =>
            store.getItems(chatId) >>= { items =>
              if items.toList.contains(newItem) then
                sendMessage(
                  ChatIntId(chatId),
                  s"$newItem is already in the list. Please use unique names."
                ).exec.void
              else
                for
                  _ <- store.addItem(chatId, newItem)
                  _ <- sendMessage(ChatIntId(chatId), s"$newItem added.").exec.void
                yield ()
            }
      yield ()

object AddItem:
  val Cmd = "/add_item"
