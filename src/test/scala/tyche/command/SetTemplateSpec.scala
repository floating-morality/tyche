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
import tyche.store.InMemStore

class SetTemplateSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val chatId = 42L

  private def makeApi(sentTexts: Ref[IO, List[String]]): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        val text = method.payload.json.hcursor.get[String]("text").toOption
        text.fold(IO.unit)(t => sentTexts.update(_ :+ t)) *>
          IO.pure(null.asInstanceOf[Res])

  private def msg(text: String) = Message(
    messageId = 1,
    date      = 0,
    chat      = Chat(id = chatId, `type` = "private"),
    text      = Some(text)
  )

  "SetTemplate" when {
    "called with a valid template" should {
      "save template to the store and confirm" in {
        for
          store     <- InMemStore.empty[IO]
          sentTexts <- Ref.of[IO, List[String]](Nil)
          _         <- {
            given Api[IO] = makeApi(sentTexts)
            SetTemplate[IO](store).onMessage(
              msg(
                s"${SetTemplate.Cmd} The Sorting Hat has decided... ${MessageTemplate.Placeholder} goes to Gryffindor!"
              )
            )
          }
          template <- store.getTemplate(chatId)
          sent     <- sentTexts.get
        yield
          template.value shouldBe "The Sorting Hat has decided... {item} goes to Gryffindor!"
          sent.head should include("The Sorting Hat has decided... {item} goes to Gryffindor!")
      }
    }

    "called without a value" should {
      "reply with usage hint" in {
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            SetTemplate[IO](store).onMessage(msg(SetTemplate.Cmd)) *> texts.get
          }
        yield sent.head should include("Usage:")
      }
    }

    "called with a template missing {item}" should {
      "reject and explain the requirement" in {
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            SetTemplate[IO](store).onMessage(msg(s"${SetTemplate.Cmd} no placeholder here")) *> texts.get
          }
        yield sent.head should include("{item}")
      }
    }
  }
