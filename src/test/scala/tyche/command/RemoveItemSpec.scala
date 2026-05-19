package tyche.command

import cats.data.Chain
import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import io.circe.Json
import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*
import tyche.store.{InMemStore, ItemStore}

class RemoveItemSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val chatId = 42L
  private val user   = User(id = 1L, isBot = false, firstName = "Test")

  private val stubMessageJson = Json.obj(
    "message_id" -> 100.asJson,
    "date"       -> 0.asJson,
    "chat"       -> Json.obj("id" -> chatId.asJson, "type" -> "private".asJson)
  )

  private val stubBoolJson = Json.fromBoolean(true)

  private def makeApi(sentTexts: Ref[IO, List[String]]): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        val text = method.payload.json.hcursor.get[String]("text").toOption
        val stub = if method.payload.name.startsWith("answer") then stubBoolJson else stubMessageJson
        text.fold(IO.unit)(t => sentTexts.update(_ :+ t)) *>
          IO.fromEither(method.decoder.decodeJson(stub).left.map(e => new RuntimeException(e.message)))

  private def storeWith(initial: Chain[String]): IO[ItemStore[IO]] =
    for
      store <- InMemStore.empty[IO]
      _     <- store.setItems(chatId, initial)
    yield store

  private val commandMsg = Message(
    messageId = 1,
    date      = 0,
    chat      = Chat(id = chatId, `type` = "private"),
    text      = Some("/remove_item")
  )

  private def callbackQuery(data: String, botMessageId: Int = 100) = CallbackQuery(
    id           = "cq-1",
    from         = user,
    chatInstance = "test",
    message      = Some(Message(messageId = botMessageId, date = 0, chat = Chat(id = chatId, `type` = "private"))),
    data         = Some(data)
  )

  "RemoveItem" when {
    "list is empty" should {
      "reply suggesting /set_items and /add_item" in {
        for
          store <- storeWith(Chain.empty)
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            RemoveItem[IO](store).onMessage(commandMsg) *> texts.get
          }
        yield sent.head should (include(SetItems.Cmd) and include(AddItem.Cmd))
      }
    }

    "list is non-empty" should {
      "offer a keyboard to choose who to remove" in {
        for
          store <- storeWith(Chain("Justine", "Monika"))
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            RemoveItem[IO](store).onMessage(commandMsg) *> texts.get
          }
        yield sent.head should include("remove")
      }
    }

    "user taps a callback button" should {
      "remove the item from the store" in {
        for
          store     <- storeWith(Chain("Justine", "Monika", "Natalia"))
          texts     <- Ref.of[IO, List[String]](Nil)
          remaining <- {
            given Api[IO] = makeApi(texts)
            RemoveItem[IO](store).onCallbackQuery(callbackQuery("remove:1")) *>
              store.getItems(chatId)
          }
        yield remaining.toList shouldBe List("Justine", "Natalia")
      }

      "confirm the removal" in {
        for
          store <- storeWith(Chain("Justine", "Monika"))
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            RemoveItem[IO](store).onCallbackQuery(callbackQuery("remove:0")) *> texts.get
          }
        yield sent should contain("Justine removed.")
      }
    }
  }
