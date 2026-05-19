package tyche.command

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.show.*
import iozhik.OpenEnum
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import telegramium.bots.*
import telegramium.bots.high.*
import telegramium.bots.high.Methods.sendMessage
import telegramium.bots.high.implicits.*
import tyche.UserShow.given
import tyche.store.{ItemStore, TemplateStore}

class Welcome[F[_]: {Monad, LoggerFactory}] private (
    items: ItemStore[F],
    templates: TemplateStore[F]
)(using Api[F]):

  private given Logger[F] = LoggerFactory[F].getLogger

  private val text =
    s"""|Hi, I'm Tyche! Happy to help with random picks.
        |
        |Use ${SetItems.Cmd} to set the list of items to choose from, and ${Random.Cmd} to randomly pick one of them.
        |More about what I can do: ${Help.Cmd}
        |
        |Privacy: I don't store the contents of your messages. I keep the items and templates you set so the commands can work. Chat and user IDs are recorded in operational logs and retained temporarily for diagnostics and service improvement. Full details: ${Welcome.PrivacyUrl}
        |""".stripMargin

  def onMyChatMember: PartialFunction[ChatMemberUpdated, F[Unit]] =
    case update if Welcome.isBotJoining(update) =>
      val chatId = update.chat.id
      for
        _ <- info"[welcome] bot added to chat=$chatId by=${update.from.show}"
        _ <- sendMessage(ChatIntId(chatId), text).exec.void
      yield ()

    case update if Welcome.isBotLeaving(update) =>
      val chatId = update.chat.id
      for
        _ <- info"[welcome] bot removed from chat=$chatId by=${update.from.show} - clearing stored data"
        _ <- items.clear(chatId)
        _ <- templates.clear(chatId)
      yield ()

object Welcome:
  val PrivacyUrl = "https://github.com/floating-morality/tyche/blob/main/PRIVACY.md"

  def apply[F[_]: {Monad, LoggerFactory}](
      items: ItemStore[F],
      templates: TemplateStore[F]
  )(using Api[F]): Welcome[F] = new Welcome[F](items, templates)

  private def isNonMember(m: OpenEnum[ChatMember]): Boolean = m match
    case OpenEnum.Known(_: ChatMemberLeft | _: ChatMemberBanned) => true
    case _                                                       => false

  private def isMember(m: OpenEnum[ChatMember]): Boolean = m match
    case OpenEnum.Known(_: ChatMemberMember | _: ChatMemberAdministrator | _: ChatMemberOwner) => true
    case _                                                                                     => false

  private def isBotJoining(memberUpdated: ChatMemberUpdated): Boolean =
    isNonMember(memberUpdated.oldChatMember) && isMember(memberUpdated.newChatMember)

  private def isBotLeaving(memberUpdated: ChatMemberUpdated): Boolean =
    isMember(memberUpdated.oldChatMember) && isNonMember(memberUpdated.newChatMember)
