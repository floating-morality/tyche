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

class Help[F[_]: {FlatMap, LoggerFactory}](using Api[F]) extends Command[F]:

  val cmd = Help.Cmd

  private given Logger[F] = LoggerFactory[F].getLogger

  private val text =
    s"""|I keep a list of items for this chat and randomly pick one on demand.
        |
        |Managing items:
        |${SetItems.Cmd} — I'll ask you to reply with items, one per line, and replace the whole list with what you send.
        |${AddItem.Cmd} <item> — I'll append a single item to the list.
        |${RemoveItem.Cmd} — I'll show a keyboard with the current items; tap one and I'll remove it.
        |${ListItems.Cmd} — I'll show the current list.
        |
        |Picking a random item:
        |${Random.Cmd} — I'll open a keyboard so you can deselect anyone who shouldn't be in the draw, then roll the dice.
        |  • 2–6 items: I'll keep rerolling until the top face shows a number within 1..N (N = items). That number is the index of the winner.
        |  • 7+ items: I'll roll once for show and then pick the winner uniformly at random; the dice face has no meaning.
        |
        |Result template:
        |${SetTemplate.Cmd} <template> — I'll use this as the announcement when picking a winner. Must contain the {item} placeholder.
        |  Example: ${SetTemplate.Cmd} The Sorting Hat has decided... {item} goes to Gryffindor!
        |${ShowTemplate.Cmd} — I'll show the current template (defaults to "I pick {item}").
        |
        |${Help.Cmd} — I'll show this message.
        |
        |Source & issues: https://github.com/floating-morality/tyche
        |""".stripMargin

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.contains(cmd) =>
      val chatId = msg.chat.id
      for
        _ <- info"[$cmd] chat=$chatId user=${msg.from.auditString}"
        _ <- sendMessage(ChatIntId(chatId), text).exec.void
      yield ()

object Help:
  val Cmd = "/help"
