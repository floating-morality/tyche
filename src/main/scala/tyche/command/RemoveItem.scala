package tyche.command

import cats.FlatMap
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.show.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import telegramium.bots.*
import telegramium.bots.high.*
import telegramium.bots.high.Methods.{answerCallbackQuery, editMessageText, sendMessage}
import telegramium.bots.high.implicits.*
import tyche.UserShow.{auditString, given}
import tyche.store.ItemStore

class RemoveItem[F[_]: {FlatMap, LoggerFactory}](store: ItemStore[F])(using Api[F]) extends Command[F]:

  val cmd = RemoveItem.Cmd

  private given Logger[F] = LoggerFactory[F].getLogger

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.exists(Command.matches(_, cmd)) =>
      val chatId = msg.chat.id
      for
        _     <- info"[$cmd] chat=$chatId user=${msg.from.auditString}"
        names <- store.getItems(chatId)
        all = names.toVector
        _ <-
          if all.isEmpty then sendMessage(ChatIntId(chatId), CommandHint.NoItems).exec.void
          else
            sendMessage(
              chatId      = ChatIntId(chatId),
              text        = "Who do you want to remove?",
              replyMarkup = InlineKeyboardMarkup(buildKeyboard(all)).some
            ).exec.void
      yield ()

  override def onCallbackQuery: PartialFunction[CallbackQuery, F[Unit]] =
    case cq if cq.data.exists(_.startsWith("remove:")) =>
      cq.message.collect { case msg: Message => msg } match
        case None      => answerCallbackQuery(cq.id).exec.void
        case Some(msg) =>
          val chatId = msg.chat.id
          val idx    = cq.data.getOrElse("").stripPrefix("remove:").toInt
          store.getItems(chatId) >>= { names =>
            names.toVector.lift(idx) match
              case None =>
                for
                  _ <- warn"[$cmd] chat=$chatId user=${cq.from.show} callback=remove index=$idx out-of-range"
                  _ <- answerCallbackQuery(cq.id).exec.void
                yield ()
              case Some(name) =>
                for
                  _ <- info"[$cmd] chat=$chatId user=${cq.from.show} callback=remove index=$idx name=$name"
                  _ <- store.removeItemAt(chatId, idx)
                  _ <- editMessageText(
                    chatId    = ChatIntId(chatId).some,
                    messageId = msg.messageId.some,
                    text      = s"$name removed."
                  ).exec.void
                  _ <- answerCallbackQuery(cq.id).exec.void
                yield ()
          }

  private def buildKeyboard(items: Vector[String]): List[List[InlineKeyboardButton]] =
    items.zipWithIndex
      .map { case (name, i) =>
        InlineKeyboardButton(text = name, callbackData = s"remove:$i".some)
      }
      .toList
      .grouped(2)
      .toList

object RemoveItem:
  val Cmd = "/remove_item"
