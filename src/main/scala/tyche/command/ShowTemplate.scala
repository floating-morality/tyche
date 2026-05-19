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
import tyche.store.TemplateStore

class ShowTemplate[F[_]: {FlatMap, LoggerFactory}](store: TemplateStore[F])(using Api[F]) extends Command[F]:

  val cmd = ShowTemplate.Cmd

  private given Logger[F] = LoggerFactory[F].getLogger

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.contains(cmd) =>
      val chatId = msg.chat.id
      for
        _        <- info"[$cmd] chat=$chatId user=${msg.from.auditString}"
        template <- store.getTemplate(chatId)
        _        <- sendMessage(ChatIntId(chatId), template.value).exec.void
      yield ()

object ShowTemplate:
  val Cmd = "/show_template"
