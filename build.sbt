name := "scala-akka-sharding"

version := "0.1"

scalaVersion := "2.13.4"


val akkaVersion = "2.6.10"
val akkaHttpVersion = "10.2.2"
val akkaManagementVersion = "1.0.9"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,

  "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % akkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaManagementVersion,
  "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime
)

enablePlugins(JavaAppPackaging, DockerPlugin, AshScriptPlugin)

maintainer in Docker := "Erwin Debusschere <erwincdl@gmail.com>"
packageName in Docker := "scala-akka-sharding"
version in Docker := "latest"
dockerUsername in Docker := Some("erwincdl")

dockerBaseImage := "adoptopenjdk/openjdk13:alpine-jre"

dockerExposedPorts := Seq(8080, 8558, 2552)
