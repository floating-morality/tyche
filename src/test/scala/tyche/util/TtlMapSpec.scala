package tyche.util

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.testkit.TestControl
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.concurrent.duration.*

class TtlMapSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  private val ttl: FiniteDuration   = 5.minutes
  private val sweep: FiniteDuration = 1.minute

  "TtlMap onEvict" when {
    "an entry expires" should {
      "be invoked by the background sweep with the key and the value" in {
        TestControl.executeEmbed {
          for
            evicted <- Ref.of[IO, List[(Long, String)]](Nil)
            _       <- TtlMap
              .makeWithOnEvict[IO, Long, String](
                ttl,
                sweep,
                onEvict = (k, v) => evicted.update(_ :+ (k, v))
              )
              .use { ttlMap =>
                for
                  _ <- ttlMap.put(1L, "a")
                  _ <- ttlMap.put(2L, "b")
                  _ <- IO.sleep(ttl + sweep + 1.second)
                yield ()
              }
            evicted <- evicted.get
          yield evicted should contain theSameElementsAs List(1L -> "a", 2L -> "b")
        }
      }

      "be invoked by a lazy get on the stale entry" in {
        TestControl.executeEmbed {
          for
            evicted <- Ref.of[IO, List[(Long, String)]](Nil)
            lookup  <- TtlMap
              .makeWithOnEvict[IO, Long, String](
                ttl,
                // sweep interval longer than wait so only lazy eviction fires
                sweepInterval = 1.hour,
                onEvict       = (k, v) => evicted.update(_ :+ (k, v))
              )
              .use { ttlMap =>
                for
                  _        <- ttlMap.put(1L, "a")
                  _        <- IO.sleep(ttl + 1.second)
                  valMaybe <- ttlMap.get(1L)
                yield valMaybe
              }
            evicted <- evicted.get
          yield
            lookup shouldBe None
            evicted shouldBe List(1L -> "a")
        }
      }
    }

    "an entry is removed explicitly" should {
      "not invoke the callback" in {
        for
          evicted <- Ref.of[IO, List[(Long, String)]](Nil)
          _       <- TtlMap
            .makeWithOnEvict[IO, Long, String](
              ttl,
              sweep,
              onEvict = (k, v) => evicted.update(_ :+ (k, v))
            )
            .use { ttlMap =>
              for
                _ <- ttlMap.put(1L, "a")
                _ <- ttlMap.remove(1L)
              yield ()
            }
          evicted <- evicted.get
        yield evicted shouldBe empty
      }
    }

    "the callback fails" should {
      "not crash the sweep fiber and keep evicting subsequent entries" in {
        TestControl.executeEmbed {
          for
            evicted <- Ref.of[IO, List[Long]](Nil)
            _       <- TtlMap
              .makeWithOnEvict[IO, Long, String](
                ttl,
                sweep,
                onEvict = (k, _) =>
                  if k === 1L then IO.raiseError(new RuntimeException("boom"))
                  else evicted.update(_ :+ k)
              )
              .use { ttlMap =>
                for
                  _ <- ttlMap.put(1L, "a")
                  _ <- ttlMap.put(2L, "b")
                  _ <- IO.sleep(ttl + sweep + 1.second)
                yield ()
              }
            seen <- evicted.get
          yield seen should contain(2L)
        }
      }
    }
  }
