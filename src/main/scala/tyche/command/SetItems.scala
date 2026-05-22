package tyche.command

import cats.Monad
import cats.data.Chain
import cats.effect.{Async, Resource}
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.show.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import telegramium.bots.*
import telegramium.bots.high.*
import telegramium.bots.high.Methods.{deleteMessage, sendMessage}
import telegramium.bots.high.implicits.*
import tyche.UserShow.given
import tyche.command.SetItems.Cmd
import tyche.store.ItemStore
import tyche.util.TtlMap

import scala.concurrent.duration.*

private final case class AwaitingItems(
    userId: Long,
    promptMessageId: Int
)

class SetItems[F[_]: {Monad, LoggerFactory}](
    store: ItemStore[F],
    awaiting: TtlMap[F, Long, AwaitingItems]
)(using api: Api[F])
    extends Command[F]:

  val cmd = SetItems.Cmd

  private given Logger[F] = LoggerFactory[F].getLogger

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.exists(Command.matches(_, cmd)) =>
      msg.from match
        case None       => ().pure[F]
        case Some(user) =>
          val chatId = msg.chat.id
          for
            _       <- info"[$Cmd] chat=$chatId user=${user.show}"
            session <- awaiting.get(chatId)
            _       <- session match
              case Some(_) =>
                warn"Skipping $Cmd by ${user.show} in chat $chatId - another prompt is already awaiting reply"
              case None =>
                startPrompt(chatId, user, replyToMessageId = msg.messageId)
          yield ()

    case msg if msg.replyToMessage.isDefined =>
      handleReply(msg)

  private def startPrompt(chatId: Long, user: User, replyToMessageId: Int): F[Unit] =
    for
      prompt <- sendMessage(
        chatId          = ChatIntId(chatId),
        text            = "Reply to this message with items, one per line:",
        replyMarkup     = ForceReply(forceReply = true, selective = true.some).some,
        replyParameters = ReplyParameters(messageId = replyToMessageId).some
      ).exec
      _ <- awaiting.put(chatId, AwaitingItems(user.id, prompt.messageId))
    yield ()

  private def handleReply(msg: Message): F[Unit] =
    val chatId = msg.chat.id
    awaiting.get(chatId) >>= { existing =>
      (existing, msg.from, msg.replyToMessage) match
        case (Some(a), Some(user), Some(reply)) if user.id === a.userId && reply.messageId === a.promptMessageId =>
          val names = Chain.fromSeq(
            msg.text.getOrElse("").linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
          )
          val auditNames = names.toList.mkString(", ")
          val dupes      = names.toList.groupBy(identity).collect { case (n, ds) if ds.sizeIs > 1 => n }
          for
            _ <- info"[$Cmd] chat=$chatId user=${user.show} reply names=[$auditNames]"
            _ <-
              if dupes.nonEmpty then
                sendMessage(
                  ChatIntId(chatId),
                  s"Duplicate names: ${dupes.mkString(", ")}. Please use unique names."
                ).exec.void
              else if names.nonEmpty then
                for
                  _ <- store.setItems(chatId, names)
                  _ <- awaiting.remove(chatId)
                  reply = names.toList.zipWithIndex
                    .map { case (name, i) => s"${i + 1}. $name" }
                    .mkString("\n")
                  _ <- sendMessage(
                    chatId = ChatIntId(chatId),
                    text   = s"Saved:\n$reply"
                  ).exec.void
                yield ()
              else sendMessage(ChatIntId(chatId), "List is empty, please try again.").exec.void
          yield ()
        case _ => ().pure[F]
    }

object SetItems:
  private val loggerName = classOf[SetItems[?]].getName
  val Cmd                = "/set_items"

  /** Creates a [[SetItems]] command, supervising an internal [[TtlMap]] of pending prompts under the returned
    * [[cats.effect.Resource]].
    *
    * @param awaitTtl
    *   how long the bot will accept a reply to its "send me the items" prompt. After this window elapses (counting from
    *   the moment the prompt was emitted), the awaiting state is forgotten — a late reply is silently ignored, and a
    *   fresh `/set_items` is allowed to start a new prompt. The prompt MESSAGE in Telegram itself never expires; only
    *   the bot's willingness to act on a reply does.
    */
  def make[F[_]: {Async, LoggerFactory}](
      store: ItemStore[F],
      awaitTtl: FiniteDuration
  )(using Api[F]): Resource[F, SetItems[F]] =
    given Logger[F] = LoggerFactory[F].getLoggerFromName(loggerName)
    TtlMap
      .makeWithOnEvict[F, Long, AwaitingItems](
        awaitTtl,
        TtlMap.DefaultSweepInterval,
        onEvict = (chatId, awaiting) =>
          deleteMessage(ChatIntId(chatId), awaiting.promptMessageId).exec.void
            .onError { case e =>
              warn"Failed to delete orphaned $Cmd prompt chat=$chatId msg=${awaiting.promptMessageId}: ${e.getMessage}"
            }
      )
      .map(new SetItems(store, _))
