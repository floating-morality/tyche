package tyche.command

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.applicative.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*

class HelpSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val chatId = 42L

  private def makeApi(sentTexts: Ref[IO, List[String]]): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        val text = method.payload.json.hcursor.get[String]("text").toOption
        for
          _   <- text.fold(IO.unit)(t => sentTexts.update(_ :+ t))
          res <- null.asInstanceOf[Res].pure[IO]
        yield res

  private val msg = Message(
    messageId = 1,
    date      = 0,
    chat      = Chat(id = chatId, `type` = "private"),
    text      = Some(Help.Cmd)
  )

  "Help" when {
    "the /help command is received" should {
      "reply with a message listing the bot's commands" in {
        for
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            Help[IO].onMessage(msg) *> texts.get
          }
        yield
          sent should have size 1
          sent.head should (include(SetItems.Cmd) and include(Random.Cmd) and include(SetTemplate.Cmd))
      }
    }
  }
