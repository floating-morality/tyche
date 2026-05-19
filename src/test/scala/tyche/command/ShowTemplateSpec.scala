package tyche.command

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*
import tyche.domain.MessageTemplate
import tyche.store.{InMemStore, TemplateStore}

class ShowTemplateSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val chatId = 42L

  private def makeApi(sentTexts: Ref[IO, List[String]]): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        val text = method.payload.json.hcursor.get[String]("text").toOption
        text.fold(IO.unit)(t => sentTexts.update(_ :+ t)) *>
          IO.pure(null.asInstanceOf[Res])

  private def storeWith(template: String): IO[TemplateStore[IO]] =
    for
      store <- InMemStore.empty[IO]
      _     <- store.setTemplate(chatId, MessageTemplate(template))
    yield store

  private val msg = Message(
    messageId = 1,
    date      = 0,
    chat      = Chat(id = chatId, `type` = "private"),
    text      = Some(ShowTemplate.Cmd)
  )

  "ShowTemplate" when {
    "template is set" should {
      "reply with the current template" in {
        for
          store <- storeWith("The Sorting Hat has decided... {item} goes to Gryffindor!")
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            ShowTemplate[IO](store).onMessage(msg) *> texts.get
          }
        yield sent.head shouldBe "The Sorting Hat has decided... {item} goes to Gryffindor!"
      }
    }
  }
