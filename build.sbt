// Global settings
ThisBuild / organization := "com.andy327"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.16"

val baseName = "pekko-games"

val versions: Map[String, String] = Map(
  "pekko" -> "1.1.3",
  "pekko-http" -> "1.2.0",
  "scalatest" -> "3.2.19"
)

lazy val commonSettings = Seq(
  scalacOptions += "-deprecation"
)

lazy val model = (project in file(s"$baseName-model"))
  .settings(
    commonSettings,
    name := s"$baseName-model",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % versions("scalatest") % Test
    )
  )

lazy val server = (project in file(s"$baseName-server"))
  .dependsOn(model)
  .settings(
    commonSettings,
    name := s"$baseName-server",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed"         % versions("pekko"),
      "org.apache.pekko" %% "pekko-http"                % versions("pekko-http"),
      "org.apache.pekko" %% "pekko-stream"              % versions("pekko"),
      "org.apache.pekko" %% "pekko-http-spray-json"     % versions("pekko-http"),
      "org.scalatest"    %% "scalatest"                 % versions("scalatest") % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % versions("pekko") % Test,
      "org.apache.pekko" %% "pekko-http-testkit"        % versions("pekko-http") % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(model, server)
  .settings(
    commonSettings,
    name := baseName
  )
