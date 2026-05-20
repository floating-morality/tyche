package tyche.command

import cats.effect.std.SecureRandom
import cats.effect.syntax.resource.*
import cats.effect.{Async, Resource, Temporal}
import cats.syntax.applicative.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.show.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import telegramium.bots.*
import telegramium.bots.high.*
import telegramium.bots.high.Methods.*
import telegramium.bots.high.implicits.*
import tyche.UserShow.{auditString, given}
import tyche.store.{ItemStore, TemplateStore}
import tyche.util.TtlMap

import scala.concurrent.duration.*

private final case class RandomSession(
    items: Vector[String],
    selected: Set[Int],
    messageId: Int
)

class Random[F[_]: {Temporal, LoggerFactory}](
    store: ItemStore[F],
    templates: TemplateStore[F],
    sessions: TtlMap[F, Long, RandomSession],
    secureRandom: SecureRandom[F]
)(using Api[F])
    extends Command[F]:

  private given Logger[F] = LoggerFactory[F].getLogger

  val cmd = Random.Cmd

  def onMessage: PartialFunction[Message, F[Unit]] =
    case msg if msg.text.exists(Command.matches(_, cmd)) =>
      val chatId = msg.chat.id
      val byUser = msg.from.auditString
      for
        _            <- info"[$cmd] chat=$chatId user=$byUser"
        sessionMaybe <- sessions.get(chatId)
        _            <- sessionMaybe match
          case Some(_) => warn"Skipping $cmd by $byUser in chat $chatId - active session exists"
          case None    => startNewSession(chatId)
      yield ()

  private def startNewSession(chatId: Long): F[Unit] =
    for
      names <- store.getItems(chatId)
      all = names.toVector
      _ <-
        if all.size === 0 then sendMessage(ChatIntId(chatId), CommandHint.NoItems).exec.void
        else if all.size === 1 then
          sendMessage(
            ChatIntId(chatId),
            s"At least two items should be set. ${CommandHint.AddItems}"
          ).exec.void
        else
          val allIndices = all.indices.toSet
          for
            sentMsg <- sendMessage(
              chatId      = ChatIntId(chatId),
              text        = "Pick from:",
              replyMarkup = InlineKeyboardMarkup(buildKeyboard(all, allIndices)).some
            ).exec
            _ <- sessions.put(chatId, RandomSession(all, allIndices, sentMsg.messageId))
          yield ()
    yield ()

  override def onCallbackQuery: PartialFunction[CallbackQuery, F[Unit]] =
    case cq if cq.data.exists(_.startsWith("random:")) =>
      cq.message.collect { case msg: Message => msg.chat.id } match
        case None         => answerCallbackQuery(cq.id).exec.void
        case Some(chatId) =>
          val byUser = cq.from.show
          cq.data.getOrElse("") match
            case s"random:toggle:$idx" =>
              for
                _ <- info"[$cmd] chat=$chatId user=$byUser callback=toggle index=$idx"
                _ <- handleToggle(cq, chatId, idx.toInt)
              yield ()
            case "random:roll" =>
              for
                _ <- info"[$cmd] chat=$chatId user=$byUser callback=roll"
                _ <- handleRoll(cq, chatId)
              yield ()
            case "random:expired" =>
              for
                _ <- info"[$cmd] chat=$chatId user=$byUser callback=expired"
                _ <- answerCallbackQuery(cq.id).exec.void
              yield ()
            case other =>
              for
                _ <- warn"[$cmd] chat=$chatId user=$byUser callback=unknown data=$other"
                _ <- answerCallbackQuery(cq.id).exec.void
              yield ()

  private def handleToggle(cq: CallbackQuery, chatId: Long, idx: Int): F[Unit] =
    sessions.get(chatId) >>= {
      case None    => showExpired(cq, chatId)
      case Some(s) => applyToggle(cq, chatId, s, idx)
    }

  private def applyToggle(cq: CallbackQuery, chatId: Long, session: RandomSession, idx: Int): F[Unit] =
    val newSelected =
      if session.selected(idx) then session.selected - idx
      else session.selected + idx
    for
      _ <- sessions.modify(chatId, _.copy(selected = newSelected))
      _ <- editMessageText(
        chatId      = ChatIntId(chatId).some,
        messageId   = session.messageId.some,
        text        = "Pick from:",
        replyMarkup = InlineKeyboardMarkup(buildKeyboard(session.items, newSelected)).some
      ).exec.void
      _ <- answerCallbackQuery(cq.id).exec.void
    yield ()

  private def handleRoll(cq: CallbackQuery, chatId: Long): F[Unit] =
    sessions.get(chatId) >>= {
      case None                             => showExpired(cq, chatId)
      case Some(s) if s.selected.sizeIs < 2 =>
        answerCallbackQuery(cq.id, text = "Select at least 2 items.".some, showAlert = true.some).exec.void
      case Some(s) => rollWinner(cq, chatId, s)
    }

  // `selected(winnerIdx)` is safe by construction: roll* return an index in [0, selected.size - 1].
  @SuppressWarnings(Array("org.wartremover.warts.SeqApply"))
  private def rollWinner(cq: CallbackQuery, chatId: Long, session: RandomSession): F[Unit] =
    val selected  = session.selected.toVector.sorted.map(session.items)
    val rollLabel = selected.zipWithIndex.map { case (name, i) => s"${i + 1}. $name" }.mkString("\n")
    for
      _        <- info"[$cmd] chat=$chatId rolling count=${selected.size} selected=[${selected.mkString(", ")}]"
      template <- templates.getTemplate(chatId)
      _        <- sessions.remove(chatId)
      _        <- answerCallbackQuery(cq.id).exec.void
      _        <- editMessageReplyMarkup(chatId = ChatIntId(chatId).some, messageId = session.messageId.some).exec.void
      _        <- sendMessage(ChatIntId(chatId), s"Choosing from:\n$rollLabel").exec.void
      _        <- Temporal[F].sleep(2.seconds)
      winnerIdxOpt <-
        if selected.sizeIs <= 6 then
          for
            _ <-
              if selected.sizeIs < 6 then {
                for
                  _ <- sendMessage(
                    ChatIntId(chatId),
                    s"I'll keep rolling the die until the top face shows a value from 1 to ${selected.size}."
                  ).exec.void
                  _ <- Temporal[F].sleep(2.seconds)
                yield ()
              } else ().pure[F]
            result <- rollUntilInRange(chatId, selected.size)
          yield result
        else
          for
            _      <- rollDice(chatId)
            winner <- secureRandom.betweenInt(0, selected.size)
          yield winner.some
      _ <- winnerIdxOpt match
        case Some(idx) =>
          val winner = selected(idx)
          for
            _ <- info"[$cmd] chat=$chatId winner=$winner idx=$idx"
            _ <- sendMessage(ChatIntId(chatId), template.replace(winner)).exec.void
          yield ()
        case None =>
          sendMessage(
            ChatIntId(chatId),
            "🤖 Something went wrong with the dice roll. Please report this to the bot author: https://github.com/floating-morality/tyche/issues"
          ).exec.void
    yield ()

  private def showExpired(cq: CallbackQuery, chatId: Long): F[Unit] =
    val expiredKb = InlineKeyboardMarkup(
      List(
        List(
          InlineKeyboardButton(
            text         = "⌛ Session expired",
            callbackData = "random:expired".some
          )
        )
      )
    )

    cq.message.collect { case m: Message => m.messageId } match
      case Some(mid) =>
        for
          _ <- editMessageReplyMarkup(
            chatId      = ChatIntId(chatId).some,
            messageId   = mid.some,
            replyMarkup = expiredKb.some
          ).exec.void
          _ <- answerCallbackQuery(cq.id).exec.void
        yield ()
      case None =>
        answerCallbackQuery(cq.id).exec.void

  // Recursion-safe: the recursive call sits inside a flatMap lambda (via for-comp), so it's
  // only evaluated lazily when the previous step's effect completes.
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def rollUntilInRange(chatId: Long, count: Int): F[Option[Int]] =
    for
      diceMsg <- rollDice(chatId)
      result  <- diceMsg.dice match
        case None =>
          error"[$cmd] chat=$chatId dice message returned without dice payload: $diceMsg"
            .as(none[Int])
        case Some(dice) =>
          if dice.value <= count then (dice.value - 1).some.pure[F]
          else rollUntilInRange(chatId, count)
    yield result

  private def rollDice(chatId: Long): F[Message] =
    for
      diceMsg <- sendDice(ChatIntId(chatId), emoji = "🎲".some).exec
      _       <- Temporal[F].sleep(4.seconds)
    yield diceMsg

  private def buildKeyboard(items: Vector[String], selected: Set[Int]): List[List[InlineKeyboardButton]] =
    val pickRow            = List(InlineKeyboardButton(text = "🎲", callbackData = "random:roll".some))
    val toggleRowsReversed = items.zipWithIndex
      .map { case (name, i) =>
        val label = if selected(i) then s"✅ $name" else s"❌ $name"
        InlineKeyboardButton(text = label, callbackData = s"random:toggle:$i".some)
      }
      .grouped(2)
      .foldLeft(List.empty[List[InlineKeyboardButton]]) { (acc, row) => row.toList :: acc }
    (pickRow :: toggleRowsReversed).reverse

object Random:
  val Cmd = "/random"

  def make[F[_]: {Async, LoggerFactory}](
      store: ItemStore[F],
      templates: TemplateStore[F],
      sessionTtl: FiniteDuration
  )(using Api[F]): Resource[F, Random[F]] =
    for
      secureRandom <- SecureRandom.javaSecuritySecureRandom[F].toResource
      sessions     <- TtlMap.make[F, Long, RandomSession](sessionTtl)
    yield new Random(store, templates, sessions, secureRandom)
