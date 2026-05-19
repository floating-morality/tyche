package tyche

import cats.effect.{IO, IOApp}
import cats.syntax.option.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`User-Agent`
import org.http4s.ProductId
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}
import telegramium.bots.high.{Api, BotApi}
import tyche.config.TycheConfig
import tyche.store.FileStore

object Main extends IOApp.Simple:

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private given Logger[IO]        = LoggerFactory[IO].getLogger

  def run: IO[Unit] =
    val app =
      for
        config     <- TycheConfig.load[IO]
        httpClient <- EmberClientBuilder
          .default[IO]
          .withUserAgent(`User-Agent`(ProductId("tyche", "0.0.1".some)))
          .build
        store <- FileStore.make[IO](config.itemsFile)
        bot   <- {
          given Api[IO] = BotApi(httpClient, baseUrl = s"https://api.telegram.org/bot${config.botToken}")
          TycheBot[IO](store, store, config.ttl)
        }
      yield bot

    app.use { bot =>
      for
        - <- info"Tyche bot started"
        _ <- bot.start()
      yield ()
    }
