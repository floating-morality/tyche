package tyche

import cats.data.Chain
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.parallel.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import tyche.domain.MessageTemplate
import tyche.store.FileStore

import java.nio.file.{Files, Path}

class FileStoreSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def withPath[A](test: Path => IO[A]): IO[A] =
    IO.blocking(Files.createTempFile("tyche-test-", ".json")) >>= { path =>
      test(path).guarantee(IO.blocking(Files.deleteIfExists(path)).void)
    }

  private def withStore[A](test: FileStore[IO] => IO[A]): IO[A] =
    withPath(FileStore.make[IO](_).use(test))

  "FileStore" when {
    "concurrent sets across different chats" should {
      "preserve all updates without races" in withStore { store =>
        val n = 2000
        for
          _ <- (0 until n).toList.parTraverse_ { i =>
            store.setItems(i.toLong, Chain(s"item-$i"))
          }
          _ <- (0 until n).toList.traverse_ { i =>
            store.getItems(i.toLong) >>= { items =>
              IO(items shouldBe Chain(s"item-$i"))
            }
          }
        yield ()
      }
    }

    "concurrent appends within the same chat" should {
      "not lose any entry" in withStore { store =>
        val chatId = 1L
        val n      = 1000
        for
          _ <- (0 until n).toList.parTraverse_ { i =>
            store.addItem(chatId, s"item-$i")
          }
          _ <- store.getItems(chatId).map { items =>
            items.length shouldBe n
          }
        yield ()
      }
    }

    "a template is set" should {
      "return that template on getTemplate" in withStore { store =>
        val chatId = 1L
        val tmplt  = MessageTemplate("Picked: {item}")
        for
          _           <- store.setTemplate(chatId, tmplt)
          storedTmplt <- store.getTemplate(chatId)
        yield storedTmplt shouldBe tmplt
      }
    }

    "no template was ever set for a chat" should {
      "return the default template" in withStore { store =>
        store.getTemplate(999L).map(_ shouldBe MessageTemplate.Default)
      }
    }

    "removeAt is called" should {
      "drop the item at the given index" in withStore { store =>
        val chatId = 1L
        for
          _    <- store.setItems(chatId, Chain("a", "b", "c"))
          _    <- store.removeItemAt(chatId, 1)
          left <- store.getItems(chatId)
        yield left.toList shouldBe List("a", "c")
      }

      "leave the list unchanged for an out-of-range index" in withStore { store =>
        val chatId = 1L
        for
          _            <- store.setItems(chatId, Chain("a", "b"))
          _            <- store.removeItemAt(chatId, 42)
          storedValues <- store.getItems(chatId)
        yield storedValues shouldBe Chain("a", "b")
      }
    }

    "clear is called for a chat" should {
      "drop both items and template for that chat, leaving other chats intact" in withStore { store =>
        for
          _             <- store.setItems(1L, Chain("a", "b"))
          _             <- store.setTemplate(1L, MessageTemplate("Winner: {item}"))
          _             <- store.setItems(2L, Chain("x"))
          _             <- store.clear(1L)
          chat1Items    <- store.getItems(1L)
          chat1Template <- store.getTemplate(1L)
          chat2Items    <- store.getItems(2L)
        yield
          chat1Items shouldBe Chain.empty
          chat1Template shouldBe MessageTemplate.Default
          chat2Items shouldBe Chain("x")
      }

      "be a no-op for a chat that has no stored data" in withStore { store =>
        for
          _     <- store.setItems(1L, Chain("a"))
          _     <- store.clear(999L)
          names <- store.getItems(1L)
        yield names shouldBe Chain("a")
      }

      "persist the removal across store restarts" in withPath { path =>
        for
          _ <- FileStore.make[IO](path).use { store =>
            for
              _ <- store.setItems(1L, Chain("a"))
              _ <- store.setTemplate(1L, MessageTemplate("T: {item{"))
              _ <- store.clear(1L)
            yield ()
          }
          out <- FileStore.make[IO](path).use { store =>
            (store.getItems(1L), store.getTemplate(1L)).parTupled
          }
        yield
          out._1 shouldBe Chain.empty
          out._2 shouldBe MessageTemplate.Default
      }
    }

    "a new store is opened on a path with previously persisted data" should {
      "load that data on startup" in withPath { path =>
        for
          _ <- FileStore.make[IO](path).use { store =>
            for
              _ <- store.setItems(1L, Chain("a", "b"))
              _ <- store.setTemplate(1L, MessageTemplate("Winner is: {item}"))
            yield ()
          }
          out <- FileStore.make[IO](path).use { store =>
            (store.getItems(1L), store.getTemplate(1L)).parTupled
          }
        yield
          out._1 shouldBe Chain("a", "b")
          out._2.value shouldBe "Winner is: {item}"
      }
    }
  }
