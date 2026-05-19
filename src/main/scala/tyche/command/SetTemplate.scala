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
import tyche.domain.MessageTemplate
import tyche.store.TemplateStore

class SetTemplate[F[_]: {FlatMap, LoggerFactory}](store: TemplateStore[F])(using Api[F]) extends Command[F]:

  val cmd = SetTemplate.Cmd

  private given Logger[F] = LoggerFactory[F].getLogger

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.exists(Command.matches(_, cmd)) =>
      val chatId   = msg.chat.id
      val template = msg.text.map(_.stripPrefix(cmd).dropWhile(_.isWhitespace)).filter(_.nonEmpty)
      for
        _ <-
          info"[$cmd] chat=$chatId user=${msg.from.auditString} template=${template.fold("<missing>")(t => s"\"$t\"")}"
        _ <- template match
          case None =>
            sendMessage(
              ChatIntId(chatId),
              s"Usage: $cmd <template>\nExample: $cmd I The Sorting Hat has decided... ${MessageTemplate.Placeholder} goes to Gryffindor!"
            ).exec.void
          case Some(t) if !t.contains(MessageTemplate.Placeholder) =>
            sendMessage(
              ChatIntId(chatId),
              s"Template must contain ${MessageTemplate.Placeholder}.\nExample: $cmd The Sorting Hat has decided... ${MessageTemplate.Placeholder} goes to Gryffindor!"
            ).exec.void
          case Some(t) =>
            for
              _ <- store.setTemplate(chatId, MessageTemplate(t))
              _ <- sendMessage(ChatIntId(chatId), s"Template saved:\n$t").exec.void
            yield ()
      yield ()

object SetTemplate:
  val Cmd = "/set_template"
