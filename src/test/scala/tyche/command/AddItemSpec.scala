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
import tyche.store.InMemStore

class AddItemSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

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

  "AddItem" when {
    "called with a name" should {
      "append to the store" in {
        for
          store <- InMemStore.empty[IO]
          _     <- {
            given Api[IO] = makeApi(Ref.unsafe[IO, List[String]](Nil))
            val cmd       = AddItem[IO](store)
            cmd.onMessage(msg("/add_item Justine")) *>
              cmd.onMessage(msg("/add_item Monika"))
          }
          names <- store.getItems(chatId)
        yield names.toList shouldBe List("Justine", "Monika")
      }

      "confirm each addition" in {
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            val cmd       = AddItem[IO](store)
            cmd.onMessage(msg("/add_item Justine")) *>
              cmd.onMessage(msg("/add_item Monika")) *>
              texts.get
          }
        yield sent should (contain("Justine added.") and contain("Monika added."))
      }
    }

    "called with a duplicate name" should {
      "reject and mention uniqueness" in {
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            val cmd       = AddItem[IO](store)
            cmd.onMessage(msg("/add_item Justine")) *>
              cmd.onMessage(msg("/add_item Justine")) *>
              texts.get
          }
        yield sent.last should (include("already") and include("Justine"))
      }
    }

    "called without a name" should {
      "reply with usage" in {
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            AddItem[IO](store).onMessage(msg("/add_item")) *> texts.get
          }
        yield sent.head should include("Usage:")
      }
    }
  }
