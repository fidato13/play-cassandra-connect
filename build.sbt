name := """play-cassandra-connect"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % Test,
    "com.datastax.cassandra" % "cassandra-driver-core" % "3.1.0",
"com.datastax.cassandra" % "cassandra-driver-mapping" % "3.1.0",
"com.datastax.cassandra" % "cassandra-driver-extras" % "3.1.0",
"org.apache.cassandra" % "cassandra-all" % "2.1.12",
  "com.typesafe.akka" %% "akka-stream" % "2.4.2"

)

