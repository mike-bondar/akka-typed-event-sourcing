ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.7"

val circeVersion = "0.14.1"

lazy val root = (project in file("."))
  .settings(
    name := "remittance",
    scalacOptions ++= Seq("-deprecation"),
    idePackagePrefix := Some("xyz.mibon.remittance"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.6.17",
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.17",
      "com.typesafe.akka" %% "akka-stream" % "2.6.17",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.14.1",
      "org.scalatest" %% "scalatest" % "3.2.10" % Test
    ) ++ Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion)
  )
