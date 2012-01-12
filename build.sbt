sbtPlugin := true

organization := "com.github.jrray"

name := "sbt-zeroc-ice"

version := "0.0.1"

scalacOptions := Seq("-deprecation", "-unchecked")

publishTo := Some(Resolver.file("jrray@github", file(Path.userHome + "/dev/repo")))
