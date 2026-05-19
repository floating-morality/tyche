package tyche.command

import cats.data.Chain
import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.testkit.TestControl
import io.circe.Json
import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*
import tyche.domain.MessageTemplate
import tyche.store.InMemStore

import scala.concurrent.duration.*

class RandomSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  private val sessionTtl: FiniteDuration = 5.minutes

  private val chatId       = 42L
  private val botMessageId = 100
  private val user         = User(id = 1L, isBot = false, firstName = "Test")

  private val stubMessageJson = Json.obj(
    "message_id" -> botMessageId.asJson,
    "date"       -> 0.asJson,
    "chat"       -> Json.obj("id" -> chatId.asJson, "type" -> "private".asJson)
  )

  private val stubBoolJson = Json.fromBoolean(true)

  private def diceMessageJson(value: Int) = Json.obj(
    "message_id" -> botMessageId.asJson,
    "date"       -> 0.asJson,
    "chat"       -> Json.obj("id" -> chatId.asJson, "type" -> "private".asJson),
    "dice"       -> Json.obj("emoji" -> "🎲".asJson, "value" -> value.asJson)
  )

  private def extractButtonLabels(json: Json): List[String] =
    json.hcursor
      .downField("reply_markup")
      .downField("inline_keyboard")
      .as[List[List[Json]]]
      .toOption
      .toList
      .flatten
      .flatten
      .flatMap(_.hcursor.get[String]("text").toOption)

  private def makeApi(sentTexts: Ref[IO, List[String]], sentLabels: Ref[IO, List[String]]): Api[IO] =
    makeDiceApi(sentTexts, sentLabels, diceValues = Ref.unsafe[IO, List[Int]](Nil), diceCount = Ref.unsafe[IO, Int](0))

  private def makeDiceApi(
      sentTexts: Ref[IO, List[String]],
      sentLabels: Ref[IO, List[String]],
      diceValues: Ref[IO, List[Int]],
      diceCount: Ref[IO, Int]
  ): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        val json    = method.payload.json
        val text    = json.hcursor.get[String]("text").toOption
        val labels  = extractButtonLabels(json)
        val capture =
          text.fold(IO.unit)(t => sentTexts.update(_ :+ t)) *>
            (if labels.nonEmpty then sentLabels.update(_ ++ labels) else IO.unit)
        method.payload.name match
          case "sendDice" =>
            diceCount.update(_ + 1) *>
              diceValues
                .modify {
                  case head :: tail => (tail, head)
                  case Nil          => (Nil, 1)
                }
                .flatMap { value =>
                  IO.fromEither(
                    method.decoder.decodeJson(diceMessageJson(value)).left.map(e => new RuntimeException(e.message))
                  )
                }
          case name if name.startsWith("answer") =>
            capture *> IO.fromEither(
              method.decoder.decodeJson(stubBoolJson).left.map(e => new RuntimeException(e.message))
            )
          case _ =>
            capture *> IO.fromEither(
              method.decoder.decodeJson(stubMessageJson).left.map(e => new RuntimeException(e.message))
            )

  private def storeWith(names: Chain[String]): IO[InMemStore[IO]] =
    for
      store <- InMemStore.empty[IO]
      _     <- store.setItems(chatId, names)
      _     <- store.setTemplate(chatId, MessageTemplate("I pick {item}"))
    yield store

  private val commandMsg = Message(
    messageId = 1,
    date      = 0,
    chat      = Chat(id = chatId, `type` = "private"),
    text      = Some("/random")
  )

  private def callbackQuery(data: String) = CallbackQuery(
    id           = "cq-1",
    from         = user,
    chatInstance = "test",
    message      = Some(Message(messageId = botMessageId, date = 0, chat = Chat(id = chatId, `type` = "private"))),
    data         = Some(data)
  )

  "Random" when {
    "there are fewer than 2 items" should {
      "suggest adding items" in {
        for
          store  <- storeWith(Chain("Justine"))
          texts  <- Ref.of[IO, List[String]](Nil)
          labels <- Ref.of[IO, List[String]](Nil)
          sent   <- {
            given Api[IO] = makeApi(texts, labels)
            Random.make[IO](store, store, sessionTtl).use { rp =>
              rp.onMessage(commandMsg) *> texts.get
            }
          }
        yield sent.head should (include(SetItems.Cmd) and include(AddItem.Cmd))
      }
    }

    "there are enough items" should {
      "show a selection keyboard" in {
        for
          store  <- storeWith(Chain("Justine", "Monika", "Natalia"))
          texts  <- Ref.of[IO, List[String]](Nil)
          labels <- Ref.of[IO, List[String]](Nil)
          sent   <- {
            given Api[IO] = makeApi(texts, labels)
            Random.make[IO](store, store, sessionTtl).use { rp =>
              rp.onMessage(commandMsg) *> texts.get
            }
          }
        yield sent.head should include("Pick from:")
      }

      "render keyboard with items in order and die emoji button last" in {
        for
          store    <- storeWith(Chain("Justine", "Monika", "Natalia"))
          texts    <- Ref.of[IO, List[String]](Nil)
          labels   <- Ref.of[IO, List[String]](Nil)
          captured <- {
            given Api[IO] = makeApi(texts, labels)
            Random.make[IO](store, store, sessionTtl).use { rp =>
              rp.onMessage(commandMsg) *> labels.get
            }
          }
        yield captured should contain theSameElementsInOrderAs List(
          "✅ Justine",
          "✅ Monika",
          "✅ Natalia",
          "🎲"
        )
      }
    }

    "user toggles an item" should {
      "update the keyboard" in {
        for
          store  <- storeWith(Chain("Justine", "Monika"))
          texts  <- Ref.of[IO, List[String]](Nil)
          labels <- Ref.of[IO, List[String]](Nil)
          sent   <- {
            given Api[IO] = makeApi(texts, labels)
            Random.make[IO](store, store, sessionTtl).use { rp =>
              rp.onMessage(commandMsg) *>
                rp.onCallbackQuery(callbackQuery("random:toggle:0")) *>
                texts.get
            }
          }
        yield sent should contain("Pick from:")
      }
    }

    "user rolls with fewer than 2 selected" should {
      "show an alert" in {
        for
          store  <- storeWith(Chain("Justine", "Monika", "Natalia"))
          texts  <- Ref.of[IO, List[String]](Nil)
          labels <- Ref.of[IO, List[String]](Nil)
          sent   <- {
            given Api[IO] = makeApi(texts, labels)
            Random.make[IO](store, store, sessionTtl).use { rp =>
              rp.onMessage(commandMsg) *>
                rp.onCallbackQuery(callbackQuery("random:toggle:0")) *>
                rp.onCallbackQuery(callbackQuery("random:toggle:1")) *>
                rp.onCallbackQuery(callbackQuery("random:toggle:2")) *>
                rp.onCallbackQuery(callbackQuery("random:roll")) *>
                texts.get
            }
          }
        yield sent.last should include("Select at least 2")
      }
    }

    "user rolls for 2 items" should {
      "reroll until dice value is in range" in {
        val rolls = List(3, 5, 4, 6, 5, 1)

        TestControl.executeEmbed {
          for
            texts     <- Ref.of[IO, List[String]](Nil)
            labels    <- Ref.of[IO, List[String]](Nil)
            diceRef   <- Ref.of[IO, List[Int]](rolls)
            diceCount <- Ref.of[IO, Int](0)
            store     <- storeWith(Chain("Justine", "Monika"))
            count     <- {
              given Api[IO] = makeDiceApi(texts, labels, diceRef, diceCount)
              Random.make[IO](store, store, sessionTtl).use { rp =>
                rp.onMessage(commandMsg) *>
                  rp.onCallbackQuery(callbackQuery("random:roll")) *>
                  diceCount.get
              }
            }
          yield count shouldBe 6
        }
      }

      "announce the winner based on dice value" in {
        TestControl.executeEmbed {
          for
            texts     <- Ref.of[IO, List[String]](Nil)
            labels    <- Ref.of[IO, List[String]](Nil)
            diceRef   <- Ref.of[IO, List[Int]](List(2))
            diceCount <- Ref.of[IO, Int](0)
            store     <- storeWith(Chain("Justine", "Monika"))
            sent      <- {
              given Api[IO] = makeDiceApi(texts, labels, diceRef, diceCount)
              Random.make[IO](store, store, sessionTtl).use { rp =>
                rp.onMessage(commandMsg) *>
                  rp.onCallbackQuery(callbackQuery("random:roll")) *>
                  texts.get
              }
            }
          yield sent.last should include("I pick Monika")
        }
      }
    }

    "a /random comes in while a session is already active" should {
      "log a warning and not start a second session" in {
        for
          store  <- storeWith(Chain("Justine", "Monika"))
          texts  <- Ref.of[IO, List[String]](Nil)
          labels <- Ref.of[IO, List[String]](Nil)
          sent   <- {
            given Api[IO] = makeApi(texts, labels)
            Random.make[IO](store, store, sessionTtl).use { rp =>
              rp.onMessage(commandMsg) *> rp.onMessage(commandMsg) *> texts.get
            }
          }
        yield sent.count(_ == "Pick from:") shouldBe 1
      }
    }

    "a session is older than SessionTtl" should {
      "allow a new /random to start" in {
        TestControl.executeEmbed {
          for
            store  <- storeWith(Chain("Justine", "Monika"))
            texts  <- Ref.of[IO, List[String]](Nil)
            labels <- Ref.of[IO, List[String]](Nil)
            sent   <- {
              given Api[IO] = makeApi(texts, labels)
              Random.make[IO](store, store, sessionTtl).use { rp =>
                rp.onMessage(commandMsg) *>
                  IO.sleep(sessionTtl + 1.second) *>
                  rp.onMessage(commandMsg) *>
                  texts.get
              }
            }
          yield sent.count(_ == "Pick from:") shouldBe 2
        }
      }
    }

    "a user clicks a button on an expired-session keyboard" should {
      "replace the keyboard with a disabled `Session expired` button" in {
        TestControl.executeEmbed {
          for
            store      <- storeWith(Chain("Justine", "Monika"))
            texts      <- Ref.of[IO, List[String]](Nil)
            labels     <- Ref.of[IO, List[String]](Nil)
            sentLabels <- {
              given Api[IO] = makeApi(texts, labels)
              Random.make[IO](store, store, sessionTtl).use { rp =>
                rp.onMessage(commandMsg) *>
                  IO.sleep(sessionTtl + 1.second) *>
                  rp.onCallbackQuery(callbackQuery("random:toggle:0")) *>
                  labels.get
              }
            }
          yield sentLabels should contain("⌛ Session expired")
        }
      }
    }
  }
