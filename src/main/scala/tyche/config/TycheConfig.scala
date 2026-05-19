package tyche.config

import cats.effect.Sync
import cats.effect.syntax.resource.*
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.module.catseffect.syntax.*

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

final case class TtlConfig(
    random: FiniteDuration,
    setItems: FiniteDuration
) derives ConfigReader

final case class TycheConfig(
    botToken: String,
    itemsFile: Path,
    ttl: TtlConfig
) derives ConfigReader

object TycheConfig:
  def load[F[_]: Sync] =
    ConfigSource.default.at("tyche").loadF[F, TycheConfig]().toResource
