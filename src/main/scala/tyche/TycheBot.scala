package tyche

import cats.effect.{Async, Resource}
import cats.syntax.applicativeError.*
import cats.{Applicative, Parallel}
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import telegramium.bots.high.*
import telegramium.bots.{CallbackQuery, ChatMemberUpdated, Message}
import tyche.command.*
import tyche.config.TtlConfig
import tyche.store.{ItemStore, TemplateStore}

class TycheBot[F[_]: {Async, Parallel, LoggerFactory}] private (commands: List[Command[F]], welcome: Welcome[F])(using
    api: Api[F]
) extends LongPollBot[F](api):

  val F = summon[Applicative[F]]

  private given Logger[F] = LoggerFactory[F].getLogger

  override def onMessage(msg: Message): F[Unit] =
    commands
      .map(_.onMessage)
      .reduceOption(_ orElse _)
      .flatMap(_.lift(msg))
      .getOrElse(F.unit)
      .recoverWith(handleApiError)

  override def onCallbackQuery(cq: CallbackQuery): F[Unit] =
    commands
      .map(_.onCallbackQuery)
      .reduceOption(_ orElse _)
      .flatMap(_.lift(cq))
      .getOrElse(F.unit)
      .recoverWith(handleApiError)

  override def onMyChatMember(update: ChatMemberUpdated): F[Unit] =
    welcome.onMyChatMember.lift(update).getOrElse(F.unit).recoverWith(handleApiError)

  private def handleApiError: PartialFunction[Throwable, F[Unit]] =
    case e: FailedRequest[?] if e.errorCode.contains(403) =>
      warn"Telegram API rejected request (bot likely kicked or blocked): ${e.getMessage}"
    case e: FailedRequest[?] =>
      error"Telegram API request failed: ${e.getMessage}"

object TycheBot:
  def apply[F[_]: {Async, Parallel, LoggerFactory}](
      store: ItemStore[F],
      templates: TemplateStore[F],
      ttl: TtlConfig
  )(using Api[F]): Resource[F, TycheBot[F]] =
    for
      setItems <- SetItems.make(store, ttl.setItems)
      random   <- Random.make(store, templates, ttl.random)
    yield new TycheBot[F](
      List(
        Help(),
        setItems,
        AddItem(store),
        RemoveItem(store),
        ListItems(store),
        SetTemplate(templates),
        ShowTemplate(templates),
        random
      ),
      Welcome(store, templates)
    )
