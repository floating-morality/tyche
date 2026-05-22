package tyche.command

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.testkit.TestControl
import io.circe.Json
import io.circe.syntax.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*
import tyche.store.InMemStore
import tyche.util.TtlMap

import scala.concurrent.duration.*

class SetItemsSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private val chatId          = 42L
  private val userId          = 100L
  private val promptMessageId = 200

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val awaitTtl: FiniteDuration = 5.minutes

  private val stubMessageJson = Json.obj(
    "message_id" -> promptMessageId.asJson,
    "date"       -> 0.asJson,
    "chat"       -> Json.obj("id" -> chatId.asJson, "type" -> "private".asJson)
  )

  private def makeApi(
      sentTexts: Ref[IO, List[String]],
      deletedMessages: Ref[IO, List[Int]] = Ref.unsafe[IO, List[Int]](Nil)
  ): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        method.payload.name match
          case "deleteMessage" =>
            val msgId = method.payload.json.hcursor.get[Int]("message_id").toOption.getOrElse(-1)
            for
              _   <- deletedMessages.update(_ :+ msgId)
              res <- IO.fromEither(method.decoder.decodeJson(Json.True))
            yield res
          case _ =>
            val text = method.payload.json.hcursor.get[String]("text").toOption
            for
              _   <- text.fold(IO.unit)(t => sentTexts.update(_ :+ t))
              res <- IO.fromEither(method.decoder.decodeJson(stubMessageJson))
            yield res

  private val user = User(id = userId, isBot = false, firstName = "Test")

  private val commandMsg = Message(
    messageId = 1,
    date      = 0,
    chat      = Chat(id = chatId, `type` = "private"),
    text      = Some("/set_items"),
    from      = Some(user)
  )

  private def replyMsg(text: String) = Message(
    messageId      = 2,
    date           = 0,
    chat           = Chat(id = chatId, `type` = "private"),
    text           = Some(text),
    from           = Some(user),
    replyToMessage = Some(Message(messageId = promptMessageId, date = 0, chat = Chat(id = chatId, `type` = "private")))
  )

  "SetItems" when {
    "user sends /set_items then replies with names" should {
      "save items to the store" in {
        for
          store  <- InMemStore.empty[IO]
          texts  <- Ref.of[IO, List[String]](Nil)
          result <- {
            given Api[IO] = makeApi(texts)
            SetItems.make[IO](store, awaitTtl).use { cmd =>
              cmd.onMessage(commandMsg) *>
                cmd.onMessage(replyMsg("Justine\nMonika\nNatalia")) *>
                store.getItems(chatId)
            }
          }
        yield result.toList should contain theSameElementsInOrderAs List("Justine", "Monika", "Natalia")
      }
    }

    "user replies with duplicate names" should {
      "reject and mention the duplicates" in {
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            SetItems.make[IO](store, awaitTtl).use { cmd =>
              cmd.onMessage(commandMsg) *>
                cmd.onMessage(replyMsg("Justine\nMonika\nJustine")) *>
                texts.get
            }
          }
        yield sent.last should (include("Duplicate") and include("Justine"))
      }

      "not save to the store" in {
        for
          store <- InMemStore.empty[IO]
          _     <- {
            given Api[IO] = makeApi(Ref.unsafe[IO, List[String]](Nil))
            SetItems.make[IO](store, awaitTtl).use { cmd =>
              cmd.onMessage(commandMsg) *>
                cmd.onMessage(replyMsg("Justine\nMonika\nJustine"))
            }
          }
          stored <- store.getItems(chatId)
        yield stored.toList shouldBe empty
      }
    }

    "user replies with empty text" should {
      "send an error message" in {
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            SetItems.make[IO](store, awaitTtl).use { cmd =>
              cmd.onMessage(commandMsg) *>
                cmd.onMessage(replyMsg("   ")) *>
                texts.get
            }
          }
        yield sent.last should include("empty")
      }
    }

    "user replies after the prompt expired (past TTL)" should {
      "ignore the reply" in {
        TestControl.executeEmbed {
          for
            store  <- InMemStore.empty[IO]
            texts  <- Ref.of[IO, List[String]](Nil)
            stored <- {
              given Api[IO] = makeApi(texts)
              SetItems.make[IO](store, awaitTtl).use { cmd =>
                cmd.onMessage(commandMsg) *>
                  IO.sleep(awaitTtl + 1.second) *>
                  cmd.onMessage(replyMsg("Justine\nMonika")) *>
                  store.getItems(chatId)
              }
            }
          yield stored.toList shouldBe empty
        }
      }
    }

    "the prompt expires without a reply" should {
      "delete the orphaned prompt message via the API" in {
        TestControl.executeEmbed {
          for
            store         <- InMemStore.empty[IO]
            texts         <- Ref.of[IO, List[String]](Nil)
            deleted       <- Ref.of[IO, List[Int]](Nil)
            deletedMsgIds <- {
              given Api[IO] = makeApi(texts, deleted)
              SetItems.make[IO](store, awaitTtl).use { cmd =>
                for
                  _       <- cmd.onMessage(commandMsg)
                  _       <- IO.sleep(awaitTtl + TtlMap.DefaultSweepInterval + 1.second)
                  deleted <- deleted.get
                yield deleted
              }
            }
          yield deletedMsgIds shouldBe List(promptMessageId)
        }
      }
    }

    "a second /set_items arrives while one is active" should {
      "skip the second call (no new prompt sent)" in {
        val otherUser = User(id = 999L, isBot = false, firstName = "Other")
        val otherCmd  = commandMsg.copy(messageId = 3, from = Some(otherUser))
        for
          store <- InMemStore.empty[IO]
          texts <- Ref.of[IO, List[String]](Nil)
          sent  <- {
            given Api[IO] = makeApi(texts)
            SetItems.make[IO](store, awaitTtl).use { cmd =>
              cmd.onMessage(commandMsg) *>
                cmd.onMessage(otherCmd) *>
                texts.get
            }
          }
        yield sent.count(_.contains("Reply to this message")) shouldBe 1
      }
    }
  }
