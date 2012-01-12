package sbtzerocice

import sbt._
import Process._
import Keys._

import java.io.File


object ZeroCIcePlugin extends Plugin {
  val zerociceConfig = config("zerocice")

  val includePaths = TaskKey[Seq[File]]("include-paths", "The paths that contain *.ice dependencies.")
  val slice2java = SettingKey[String]("slice2java", "The path+name of the slice2java executable.")
  val stream = SettingKey[Boolean]("stream", "Generate marshaling support for public stream API.")
  val checksum = SettingKey[Option[String]]("checksum", "Generate checksums for Slice definitions into CLASS.")
  val externalIncludePath = SettingKey[File]("external-include-path", "The path to which zerocice:library-dependencies are extracted and which is used as zerocice:include-path for slice2java")

  val generate = TaskKey[Seq[File]]("generate", "Compile the zerocice sources.")
  val unpackDependencies = TaskKey[UnpackedDependencies]("unpack-dependencies", "Unpack dependencies.")

  def zerociceSettingsIn(c: Configuration): Seq[Setting[_]] = inConfig(c)(Seq[Setting[_]](
    sourceDirectory <<= (sourceDirectory in Compile) { _ / "slice" },
    javaSource <<= (sourceManaged in Compile) { _ / "compiled_slice" },
    externalIncludePath <<= target(_ / "zerocice_external"),
    slice2java := "slice2java",
    version := "2.4.1",
    stream := false,
    checksum := None,

    managedClasspath <<= (classpathTypes, update) map { (ct, report) =>
      Classpaths.managedJars(c, ct, report)
    },

    unpackDependencies <<= unpackDependenciesTask,

    includePaths <<= (sourceDirectory in c) map (identity(_) :: Nil),
    includePaths <+= unpackDependencies map { _.dir },

    generate <<= sourceGeneratorTask

  )) ++ Seq[Setting[_]](
    sourceGenerators in Compile <+= (generate in c),
    managedSourceDirectories in Compile <+= (javaSource in c),
    cleanFiles <+= (javaSource in c),
    // libraryDependencies <+= (version in c)("com.google.zerocice" % "zerocice-java" % _),
    ivyConfigurations += c
  )

  def zerociceSettings: Seq[Setting[_]] =
    zerociceSettingsIn(zerociceConfig)

  case class UnpackedDependencies(dir: File, files: Seq[File])

  private def executeSlice2Java(slice2javaCommand: String, srcDir: File,
                                target: File, includePaths: Seq[File],
                                stream: Boolean, checksum: Option[String], log: Logger) =
    try {
      val schemas = (srcDir ** "*.ice").get
      val incPath = includePaths.map(_.absolutePath).mkString("-I", " -I", "")
      val streamArg = if (stream) "--stream" else ""
      val checksumArg = if (checksum.isDefined) "--checksum %s".format(checksum.get) else ""
      <x>{slice2javaCommand} {streamArg} {checksumArg} {incPath} --output-dir={target.absolutePath} {schemas.map(_.absolutePath).mkString(" ")}</x> ! log
    } catch { case e: Exception =>
      throw new RuntimeException("error occured while compiling zerocice files: %s" format(e.getMessage), e)
    }


  private
  def compile(slice2javaCommand: String, srcDir: File, target: File,
              includePaths: Seq[File], stream: Boolean,
              checksum: Option[String], log: Logger) = {
    val schemas = (srcDir ** "*.ice").get
    target.mkdirs()
    log.info("Compiling %d zerocice files to %s".format(schemas.size, target))
    schemas.foreach { schema => log.info("Compiling schema %s" format schema) }

    val exitCode = executeSlice2Java(slice2javaCommand, srcDir, target,
                                     includePaths, stream, checksum, log)
    if (exitCode != 0)
      sys.error("slice2java returned exit code: %d" format exitCode)

    (target ** "*.java").get.toSet
  }

  private def unpack(deps: Seq[File], extractTarget: File, log: Logger): Seq[File] = {
    IO.createDirectory(extractTarget)
    deps.flatMap { dep =>
      val seq = IO.unzip(dep, extractTarget, "*.ice").toSeq
      if (!seq.isEmpty) log.debug("Extracted " + seq.mkString(","))
      seq
    }
  }

  private def sourceGeneratorTask =
    (streams,
     sourceDirectory in zerociceConfig,
     javaSource in zerociceConfig,
     includePaths in zerociceConfig,
     stream in zerociceConfig,
     checksum in zerociceConfig,
     cacheDirectory,
     slice2java) map {
    (out, srcDir, targetDir, includePaths, stream, checksum, cache, slice2javaCommand) =>
      val cachedCompile = FileFunction.cached(cache / "zerocice", inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) { (in: Set[File]) =>
        compile(slice2javaCommand, srcDir, targetDir, includePaths, stream, checksum, out.log)
      }
      cachedCompile((srcDir ** "*.ice").get.toSet).toSeq
  }

  private def unpackDependenciesTask = (streams, managedClasspath in zerociceConfig, externalIncludePath in zerociceConfig) map {
    (out, deps, extractTarget) =>
      val extractedFiles = unpack(deps.map(_.data), extractTarget, out.log)
      UnpackedDependencies(extractTarget, extractedFiles)
  }

}
