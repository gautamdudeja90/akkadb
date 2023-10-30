val scala3Version = "3.3.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "AkkaDB",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.6.17" cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-http" % "10.2.7" cross CrossVersion.for3Use2_13,
      "org.scalameta" %% "munit" % "0.7.29" % Test

)
  )
