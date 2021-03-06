package sbtzerocice

import sbt._
import Keys._

import java.io.File


object ZeroCIcePlugin extends Plugin {
  import IceKeys._

  object IceKeys {
    val slice2java = taskKey[Seq[File]]("Generate java sources.")
    val slice2javaBin = settingKey[String]("The path+name of the slice2java executable.")

    val includePaths = taskKey[Seq[File]]("The paths that contain *.ice dependencies.")
    val stream = settingKey[Boolean]("Generate marshaling support for public stream API.")
    val underscore = settingKey[Boolean]("Permit use of underscores in slice identifiers.")
    val checksum = settingKey[Option[String]]("Generate checksums for Slice definitions into CLASS.")
    val externalIncludePath = settingKey[File]("The path to which zerocice:library-dependencies are extracted and which is used as zerocice:include-path for slice2java")

    val unpackDependencies = taskKey[UnpackedDependencies]("Unpack dependencies.")

    val filter = settingKey[FileFilter]("Filter for selecting ice sources from default directories.")
    val excludeFilter = settingKey[FileFilter]("Filter for excluding files from default directories.")
  }

  def zerociceSettingsIn(c: Configuration): Seq[Setting[_]] =
    inConfig(c)(zerociceSettings0 ++ Seq(
      (sourceDirectory in slice2java) := (sourceDirectory in c).value / "slice",
      (javaSource in slice2java) := (sourceManaged in c).value / "compiled_slice",
      externalIncludePath := target.value / "zerocice_external",

      (managedClasspath in slice2java) := Classpaths.managedJars(c, classpathTypes.value, update.value),

      unpackDependencies := {
        val extractedFiles = unpack(
          (managedClasspath in slice2java).value.map(_.data),
          (externalIncludePath in slice2java).value,
          streams.value.log)
        UnpackedDependencies(
          (externalIncludePath in slice2java).value, extractedFiles)
      },

      (includePaths in slice2java) := (sourceDirectory in c).value :: Nil,
      (includePaths in slice2java) += (unpackDependencies in slice2java).value.dir,

      (watchSources in slice2java) := (unmanagedSources in slice2java).value

    )) ++ Seq(
      (sourceGenerators in Compile) += (slice2java in c).taskValue,
      (managedSourceDirectories in Compile) += (javaSource in slice2java in c).value,
      cleanFiles += (javaSource in slice2java in c).value,
      watchSources ++= (unmanagedSources in slice2java in c).value,
      ivyConfigurations += c
    )

  def zerociceSettings: Seq[Setting[_]] =
    zerociceSettingsIn(Compile)

  def zerociceSettings0: Seq[Setting[_]] = Seq(
    slice2javaBin in slice2java := "slice2java",
    stream in slice2java := false,
    underscore in slice2java := false,
    checksum in slice2java := None,
    slice2java := {
      val cachedCompile = FileFunction.cached(
        streams.value.cacheDirectory / "zerocice",
        inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) {
        (in: Set[File]) =>
          compile(
            (slice2javaBin in slice2java).value,
            (sourceDirectory in slice2java).value,
            (excludeFilter in slice2java).value,
            (javaSource in slice2java).value,
            (includePaths in slice2java).value,
            (stream in slice2java).value,
            (underscore in slice2java).value,
            (checksum in slice2java).value,
            streams.value.log)
      }
      val srcs = (sourceDirectory in slice2java).value.descendantsExcept(
        "*.ice", (excludeFilter in slice2java).value).get.toSet
      cachedCompile(srcs).toSeq
    },
    filter in slice2java := "*.ice",
    excludeFilter in slice2java := (".*" - ".") || HiddenFileFilter,
    unmanagedSources in slice2java :=
      (sourceDirectory in slice2java).value.descendantsExcept(
        (filter in slice2java).value, (excludeFilter in slice2java).value).get
  )

  case class UnpackedDependencies(dir: File, files: Seq[File])

  private def executeSlice2Java(slice2javaCommand: String, srcDir: File,
                                excludeFilter: FileFilter,
                                target: File, includePaths: Seq[File],
                                stream: Boolean, underscore: Boolean,
                                checksum: Option[String], log: Logger) =
    try {
      val schemas = (srcDir.descendantsExcept("*.ice", excludeFilter)).get
      val incPath = includePaths.map(_.absolutePath).mkString("-I", " -I", "")
      val streamArg = if (stream) "--stream" else ""
      val underscoreArg = if (underscore) "--underscore" else ""
      val checksumArg = if (checksum.isDefined) s"--checksum ${checksum.get}" else ""
      val cmd = s"""${slice2javaCommand} ${streamArg} ${underscoreArg} ${checksumArg} ${incPath} --output-dir=${target.absolutePath} ${schemas.map(_.absolutePath).mkString(" ")}"""
      log.info(s"Executing: $cmd")
      cmd ! log
    } catch { case e: Exception =>
      throw new RuntimeException(s"error occured while compiling zerocice files: ${e.getMessage}", e)
    }


  private
  def compile(slice2javaCommand: String, srcDir: File,
              excludeFilter: FileFilter, target: File,
              includePaths: Seq[File], stream: Boolean,
              underscore: Boolean,
              checksum: Option[String], log: Logger) = {
    val schemas = (srcDir.descendantsExcept("*.ice", excludeFilter)).get
    target.mkdirs()
    log.info("Compiling %d zerocice files to %s".format(schemas.size, target))
    schemas.foreach { schema => log.info("Compiling schema %s" format schema) }

    val exitCode = executeSlice2Java(slice2javaCommand, srcDir, excludeFilter,
                                     target, includePaths, stream, underscore,
                                     checksum, log)
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
}
