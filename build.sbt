scalaVersion := "2.9.2"

sbtPlugin := true

organization := "com.imageworks"

name := "sbt-zeroc-ice"

version := "0.0.5"

scalacOptions := Seq("-deprecation", "-unchecked")

publishTo := Some(Resolver.file("compile21", file("/shots/spi/home/maven_repos")))
