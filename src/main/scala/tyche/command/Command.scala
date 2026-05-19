package tyche.command

import cats.syntax.eq.*
import telegramium.bots.{CallbackQuery, Message}

trait Command[F[_]]:
  val cmd: String
  def onMessage: PartialFunction[Message, F[Unit]]
  def onCallbackQuery: PartialFunction[CallbackQuery, F[Unit]] = PartialFunction.empty

object Command:
  def matches(text: String, cmd: String): Boolean =
    text === cmd || text.startsWith(s"$cmd ") || text.startsWith(s"$cmd@")
