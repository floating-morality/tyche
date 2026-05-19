import Dependencies.*
import org.typelevel.scalacoptions.ScalacOptions

ThisBuild / version := "0.0.1"

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "tyche",
    Compile / mainClass := Some("tyche.Main"),
    // Wart.Any excluded: false-positives on string interpolation (`s"..."`, `info"..."`) because StringContext.s takes Any*.
    Compile / wartremoverErrors ++= Warts.allBut(Wart.Any, Wart.DefaultArguments),
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
    Test / scalacOptions := (Test / scalacOptions).value.filterNot(_.contains("wartremover")),
    libraryDependencies ++= Telegramium.Deps ++ Http4s.Deps ++ Circe.Deps ++ Log4cats.Deps ++ Logback.Deps ++ PureConfig.Deps ++ Testing.Deps,
    Docker / packageName := "tyche",
    dockerBaseImage      := "eclipse-temurin:25-jre-ubi10-minimal",
    dockerExposedVolumes := Seq("/opt/docker/data"),
    dockerUpdateLatest   := true,
    dockerEnvVars        := Map(
      "TYCHE_ITEMS_FILE" -> "/opt/docker/data/items.json",
      "TYCHE_LOG_DIR"                 -> "/opt/docker/data/logs"
    )
  )
