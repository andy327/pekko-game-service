// Global settings
ThisBuild / organization := "com.andy327"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.16"

// Required for Scalafix semantic rules
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-Wunused", // For RemoveUnused rule
  "-Wunused:imports" // For OrganizeImports.removeUnused = true
)

addCommandAlias("formatAll", ";scalafixAll;scalafixAll;scalafmtAll;scalafmtAll;scalafmtSbt")
addCommandAlias("ci", ";clean;scalafixAll --check;scalafmtCheckAll;scalafmtSbtCheck;coverage;test;coverageAggregate")

val baseName = "game-service"

val versions: Map[String, String] = Map(
  "circe" -> "0.14.6",
  "dimafeng" -> "0.43.0",
  "doobie" -> "1.0.0-RC8",
  "jwt-scala" -> "11.0.0",
  "pekko" -> "1.1.3",
  "pekko-http" -> "1.2.0",
  "redis4cats" -> "1.7.2",
  "scaffeine" -> "5.3.0",
  "scalatest" -> "3.2.19",
  "slf4j" -> "2.0.17",
  "testcontainers" -> "1.21.1",
  "typesafe-config" -> "1.4.3"
)

lazy val model = (project in file(s"$baseName-model"))
  .settings(
    name := s"$baseName-model",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % versions("scalatest") % Test
    )
  )

lazy val persistence = (project in file(s"$baseName-persistence"))
  .dependsOn(model)
  .settings(
    name := s"$baseName-persistence",
    // Docker Engine 20.10+ requires API >= 1.40; testcontainers' shaded docker-java defaults to 1.32
    Test / fork := true,
    Test / envVars += ("TESTCONTAINERS_RYUK_DISABLED" -> "true"),
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core" % versions("doobie"),
      "org.tpolecat" %% "doobie-postgres" % versions("doobie"),
      "org.tpolecat" %% "doobie-postgres-circe" % versions("doobie"),
      "org.tpolecat" %% "doobie-hikari" % versions("doobie"),
      "io.circe" %% "circe-core" % versions("circe"),
      "io.circe" %% "circe-generic" % versions("circe"),
      "io.circe" %% "circe-parser" % versions("circe"),
      "dev.profunktor" %% "redis4cats-effects" % versions("redis4cats"),
      "com.typesafe" % "config" % versions("typesafe-config"),
      "org.slf4j" % "slf4j-simple" % versions("slf4j"),
      "org.scalatest" %% "scalatest" % versions("scalatest") % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % versions("dimafeng") % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % versions("dimafeng") % Test,
      "org.testcontainers" % "testcontainers" % versions("testcontainers") % Test,
      "org.testcontainers" % "postgresql" % versions("testcontainers") % Test
    )
  )

lazy val server = (project in file(s"$baseName-server"))
  .dependsOn(model, persistence)
  .settings(
    name := s"$baseName-server",
    Compile / mainClass := Some("com.andy327.server.GameServer"),
    // testcontainers' shaded docker-java falls back to API v1.32 when api.version is unset;
    // Docker Engine 20.10+ requires API >= 1.40. Override with the shaded config's property key.
    Test / fork := true,
    Test / envVars += ("TESTCONTAINERS_RYUK_DISABLED" -> "true"),
    Test / javaOptions += "-Dapi.version=1.41",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _ @_*)          => MergeStrategy.discard
      case "application.conf"                   => MergeStrategy.concat
      case "reference.conf"                     => MergeStrategy.concat
      case _                                    => MergeStrategy.first
    },
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % versions("pekko"),
      "org.apache.pekko" %% "pekko-http" % versions("pekko-http"),
      "org.apache.pekko" %% "pekko-stream" % versions("pekko"),
      "org.apache.pekko" %% "pekko-stream-typed" % versions("pekko"),
      "org.apache.pekko" %% "pekko-http-spray-json" % versions("pekko-http"),
      "com.github.jwt-scala" %% "jwt-core" % versions("jwt-scala"),
      "com.github.jwt-scala" %% "jwt-circe" % versions("jwt-scala"),
      "com.github.blemale" %% "scaffeine" % versions("scaffeine"),
      "org.slf4j" % "slf4j-simple" % versions("slf4j"),
      "dev.profunktor" %% "redis4cats-streams" % versions("redis4cats"),
      "org.scalatest" %% "scalatest" % versions("scalatest") % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % versions("pekko") % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % versions("pekko-http") % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % versions("pekko") % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % versions("dimafeng") % Test,
      "org.testcontainers" % "testcontainers" % versions("testcontainers") % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(model, persistence, server)
  .dependsOn(server)
  .settings(
    name := baseName,
    Compile / run / mainClass := Some("com.andy327.server.GameServer")
  )
