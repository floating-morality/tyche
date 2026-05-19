import sbt._

object Dependencies {

  object Telegramium {
    private val org     = "io.github.apimorphism"
    private val version = "10.906.0"

    val Core = org %% "telegramium-core" % version
    // telegramium-high pulls in http4s-blaze-client (HTTP) + http4s-blaze-server (webhooks);
    // excluding both roots drops the whole blaze transitive cluster (http4s-blaze-core,
    // blaze-http, blaze-core). We use Ember for the client and don't use webhooks.
    val High = (org %% "telegramium-high" % version)
      .excludeAll(
        ExclusionRule("org.http4s", "http4s-blaze-client_3"),
        ExclusionRule("org.http4s", "http4s-blaze-server_3")
      )

    val Deps = Seq(Core, High)
  }

  object Http4s {
    val EmberClient = "org.http4s" %% "http4s-ember-client" % "0.23.34"

    val Deps = Seq(EmberClient)
  }

  object Circe {
    private val org     = "io.circe"
    private val version = "0.14.10"

    val Core    = org %% "circe-core"    % version
    val Parser  = org %% "circe-parser"  % version
    val Generic = org %% "circe-generic" % version

    val Deps = Seq(Core, Parser, Generic)
  }

  object Log4cats {
    val Slf4j = "org.typelevel" %% "log4cats-slf4j" % "2.8.0"
    val Deps  = Seq(Slf4j)
  }

  object Logback {
    val Classic = "ch.qos.logback" % "logback-classic" % "1.5.32"
    val Deps    = Seq(Classic)
  }

  object PureConfig {
    private val version = "0.17.10"

    val Generic    = "com.github.pureconfig" %% "pureconfig-generic-scala3" % version
    val CatsEffect = "com.github.pureconfig" %% "pureconfig-cats-effect"    % version

    val Deps = Seq(Generic, CatsEffect)
  }

  object Testing {
    val ScalaTest          = "org.scalatest"   %% "scalatest"                     % "3.2.20" % Test
    val CatsEffectTesting  = "org.typelevel"   %% "cats-effect-testing-scalatest" % "1.8.0"  % Test
    val CatsEffectTestkit  = "org.typelevel"   %% "cats-effect-testkit"           % "3.7.0"  % Test

    val Deps = Seq(ScalaTest, CatsEffectTesting, CatsEffectTestkit)
  }

}
