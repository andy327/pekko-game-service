// Global settings
ThisBuild / organization := "com.andy327"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.16"

val baseName = "pekko-games"

val pekkoVersion = "1.1.3"
val pekkoHttpVersion = "1.2.0"

lazy val commonSettings = Seq(
  scalacOptions += "-deprecation"
)

lazy val model = (project in file(s"$baseName-model"))
  .settings(
    commonSettings,
    name := s"$baseName-model"
  )

lazy val server = (project in file(s"$baseName-server"))
  .dependsOn(model)
  .settings(
    commonSettings,
    name := s"$baseName-server",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed"     % pekkoVersion,
      "org.apache.pekko" %% "pekko-http"            % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-stream"          % pekkoVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion
    )
  )

lazy val root = (project in file("."))
  .aggregate(model, server)
  .settings(
    commonSettings,
    name := baseName
  )
