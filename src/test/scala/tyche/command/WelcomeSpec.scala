package tyche.command

import cats.data.Chain
import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.applicative.*
import iozhik.OpenEnum
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import telegramium.bots.*
import telegramium.bots.client.Method
import telegramium.bots.high.*
import tyche.domain.MessageTemplate
import tyche.store.InMemStore

class WelcomeSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val chatId = 42L
  private val bot    = User(id = 1, isBot = true, firstName = "Tyche")
  private val actor  = User(id = 2, isBot = false, firstName = "Justine")
  private val chat   = Chat(id = chatId, `type` = "group")

  private val seedItems    = Chain("Justine", "Monika")
  private val seedTemplate = MessageTemplate("Winner: {item}")

  private def makeApi(sentTexts: Ref[IO, List[String]]): Api[IO] =
    new Api[IO]:
      def execute[Res](method: Method[Res]): IO[Res] =
        val text = method.payload.json.hcursor.get[String]("text").toOption
        for
          _   <- text.fold(IO.unit)(t => sentTexts.update(_ :+ t))
          res <- null.asInstanceOf[Res].pure[IO]
        yield res

  private def seededStore: IO[InMemStore[IO]] =
    for
      store <- InMemStore.empty[IO]
      _     <- store.setItems(chatId, seedItems)
      _     <- store.setTemplate(chatId, seedTemplate)
    yield store

  private def update(oldStatus: ChatMember, newStatus: ChatMember): ChatMemberUpdated =
    ChatMemberUpdated(
      chat          = chat,
      from          = actor,
      date          = 0L,
      oldChatMember = OpenEnum.Known(oldStatus),
      newChatMember = OpenEnum.Known(newStatus)
    )

  private val adminBot = ChatMemberAdministrator(
    user                = bot,
    canBeEdited         = true,
    isAnonymous         = false,
    canManageChat       = true,
    canDeleteMessages   = true,
    canManageVideoChats = true,
    canRestrictMembers  = true,
    canPromoteMembers   = false,
    canChangeInfo       = true,
    canInviteUsers      = true,
    canPostStories      = false,
    canEditStories      = false,
    canDeleteStories    = false
  )

  "Welcome" when {
    "the bot is added to a chat (left -> member)" should {
      "send a single greeting message with command pointers and a privacy link, and not clear any data" in {
        for
          texts <- Ref.of[IO, List[String]](Nil)
          store <- seededStore
          _     <- {
            given Api[IO] = makeApi(texts)
            val welcome   = Welcome[IO](store, store)
            welcome.onMyChatMember.lift(update(ChatMemberLeft(bot), ChatMemberMember(bot))).getOrElse(IO.unit)
          }
          sent     <- texts.get
          items    <- store.getItems(chatId)
          template <- store.getTemplate(chatId)
        yield
          sent should have size 1
          val msg = sent.head
          msg should (include(SetItems.Cmd)
            and include(Random.Cmd)
            and include(Help.Cmd)
            and include(Welcome.PrivacyUrl))
          items shouldBe seedItems
          template shouldBe seedTemplate
      }
    }

    "the bot is promoted to administrator (member -> administrator)" should {
      "not send any message and not clear any data" in {
        for
          texts <- Ref.of[IO, List[String]](Nil)
          store <- seededStore
          _     <- {
            given Api[IO] = makeApi(texts)
            val welcome   = Welcome[IO](store, store)
            welcome.onMyChatMember.lift(update(ChatMemberMember(bot), adminBot)).getOrElse(IO.unit)
          }
          sent     <- texts.get
          items    <- store.getItems(chatId)
          template <- store.getTemplate(chatId)
        yield
          sent shouldBe empty
          items shouldBe seedItems
          template shouldBe seedTemplate
      }
    }

    "the bot is removed from a chat (member -> left)" should {
      "clear stored data for that chat and not send any message" in {
        for
          texts <- Ref.of[IO, List[String]](Nil)
          store <- seededStore
          _     <- {
            given Api[IO] = makeApi(texts)
            val welcome   = Welcome[IO](store, store)
            welcome.onMyChatMember.lift(update(ChatMemberMember(bot), ChatMemberLeft(bot))).getOrElse(IO.unit)
          }
          sent     <- texts.get
          items    <- store.getItems(chatId)
          template <- store.getTemplate(chatId)
        yield
          sent shouldBe empty
          items shouldBe Chain.empty
          template shouldBe MessageTemplate.Default
      }
    }

    "the bot is banned from a chat (administrator -> banned)" should {
      "clear stored data for that chat and not send any message" in {
        val banned = ChatMemberBanned(user = bot, untilDate = 0)
        for
          texts <- Ref.of[IO, List[String]](Nil)
          store <- seededStore
          _     <- {
            given Api[IO] = makeApi(texts)
            val welcome   = Welcome[IO](store, store)
            welcome.onMyChatMember.lift(update(adminBot, banned)).getOrElse(IO.unit)
          }
          sent     <- texts.get
          items    <- store.getItems(chatId)
          template <- store.getTemplate(chatId)
        yield
          sent shouldBe empty
          items shouldBe Chain.empty
          template shouldBe MessageTemplate.Default
      }
    }

    "the transition is between two non-member states (left -> banned)" should {
      "not send any message and not clear any data" in {
        val banned = ChatMemberBanned(user = bot, untilDate = 0)
        for
          texts <- Ref.of[IO, List[String]](Nil)
          store <- seededStore
          _     <- {
            given Api[IO] = makeApi(texts)
            val welcome   = Welcome[IO](store, store)
            welcome.onMyChatMember.lift(update(ChatMemberLeft(bot), banned)).getOrElse(IO.unit)
          }
          sent     <- texts.get
          items    <- store.getItems(chatId)
          template <- store.getTemplate(chatId)
        yield
          sent shouldBe empty
          items shouldBe seedItems
          template shouldBe seedTemplate
      }
    }
  }
