package tyche.command

import cats.data.Chain
import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*
import tyche.store.{InMemStore, ItemStore}

class ListItemsSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val chatId = 42L

  private val listMessage = Message(
    messageId = 1,
    date      = 0,
    chat      = Chat(id = chatId, `type` = "private"),
    text      = Some("/list_items")
  )

  private def storeWith(names: Chain[String]): IO[ItemStore[IO]] =
    for
      store <- InMemStore.empty[IO]
      _     <- store.setItems(chatId, names)
    yield store

  private def makeApi(sentTexts: Ref[IO, List[String]]): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        val text = method.payload.json.hcursor.get[String]("text").toOption
        text.fold(IO.unit)(t => sentTexts.update(_ :+ t)) *>
          IO.pure(null.asInstanceOf[Res])

  "ListItems" when {
    "the item list is empty" should {
      "reply suggesting /set_items and /add_item" in {
        for
          store <- storeWith(Chain.empty)
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            ListItems[IO](store).onMessage(listMessage) *> texts.get
          }
        yield sent.head should (include(SetItems.Cmd) and include(AddItem.Cmd))
      }
    }

    "the item list is non-empty" should {
      "reply with numbered items" in {
        val names = Chain("Justine", "Monika", "Natalia")
        for
          store <- storeWith(names)
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            ListItems[IO](store).onMessage(listMessage) *> texts.get
          }
        yield sent.head should (include("1. Justine") and include("2. Monika") and include("3. Natalia"))
      }
    }
  }
