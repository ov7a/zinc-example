val zincVersion = System.getenv("ZINC_VERSION")

lazy val root = (project in file("."))
  .settings(
    name := "zinc-compiler-example",
    fork := true,
    scalaVersion := "2.13.15",
    libraryDependencies ++= Seq(
      "org.scala-sbt" %% "zinc" % zincVersion,
      "org.scala-sbt" %% "zinc-compile" % zincVersion,
      "org.scala-sbt" %% "util-logging" % zincVersion,
      "org.scala-sbt" %% "compiler-bridge" % zincVersion
    ),
  )