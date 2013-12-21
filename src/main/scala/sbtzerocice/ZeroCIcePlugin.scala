package sbtzerocice

import sbt._
import Process._
import Keys._

import java.io.File


object ZeroCIcePlugin extends Plugin {
  import IceKeys._

  object IceKeys {
    val slice2java = TaskKey[Seq[File]]("slice2java", "Generate java sources.")
    val slice2javaBin = SettingKey[String]("slice2java.bin", "The path+name of the slice2java executable.")

    val includePaths = TaskKey[Seq[File]]("include-paths", "The paths that contain *.ice dependencies.")
    val stream = SettingKey[Boolean]("stream", "Generate marshaling support for public stream API.")
    val underscore = SettingKey[Boolean]("underscore", "Permit use of underscores in slice identifiers.")
    val checksum = SettingKey[Option[String]]("checksum", "Generate checksums for Slice definitions into CLASS.")
    val externalIncludePath = SettingKey[File]("external-include-path", "The path to which zerocice:library-dependencies are extracted and which is used as zerocice:include-path for slice2java")

    val unpackDependencies = TaskKey[UnpackedDependencies]("zerocice-unpack-dependencies", "Unpack dependencies.")

    val filter = SettingKey[FileFilter]("filter", "Filter for selecting ice sources from default directories.")
    val excludeFilter = SettingKey[FileFilter]("exclude-filter", "Filter for excluding files from default directories.")
  }

  private def zerociceSourcesTask =
    (sourceDirectory in slice2java, filter in slice2java, excludeFilter in slice2java) map {
      (sourceDir, filt, excl) =>
        sourceDir.descendantsExcept(filt, excl).get
    }

  def zerociceSettingsIn(c: Configuration): Seq[Setting[_]] =
    inConfig(c)(zerociceSettings0 ++ Seq(
      sourceDirectory in slice2java <<= (sourceDirectory in c) { _ / "main" / "slice" },
      javaSource in slice2java <<= (sourceManaged in c) { _ / "compiled_slice" },
      externalIncludePath <<= target(_ / "zerocice_external"),

      managedClasspath <<= (classpathTypes, update) map { (ct, report) =>
        Classpaths.managedJars(c, ct, report)
      },

      unpackDependencies <<= unpackDependenciesTask,

      includePaths in slice2java <<= (sourceDirectory in c) map (identity(_) :: Nil),
      includePaths in slice2java <+= unpackDependencies in slice2java map { _.dir },

      watchSources in slice2java <<= (unmanagedSources in slice2java)

    )) ++ Seq(
      sourceGenerators in Compile <+= (slice2java in c),
      managedSourceDirectories in Compile <+= (javaSource in slice2java in c),
      cleanFiles <+= (javaSource in slice2java in c),
      watchSources <++= (unmanagedSources in slice2java in c),
      // libraryDependencies <+= (version in c)("com.google.zerocice" % "zerocice-java" % _),
      ivyConfigurations += c
    )

  def zerociceSettings: Seq[Setting[_]] =
    zerociceSettingsIn(Compile)

  def zerociceSettings0: Seq[Setting[_]] = Seq(
    slice2javaBin in slice2java := "slice2java",
    stream in slice2java := false,
    underscore in slice2java := false,
    checksum in slice2java := None,
    slice2java <<= sourceGeneratorTask,
    filter in slice2java := "*.ice",
    excludeFilter in slice2java := (".*" - ".") || HiddenFileFilter,
    unmanagedSources in slice2java <<= zerociceSourcesTask
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
      val checksumArg = if (checksum.isDefined) "--checksum %s".format(checksum.get) else ""
      <x>{slice2javaCommand} {streamArg} {underscoreArg} {checksumArg} {incPath} --output-dir={target.absolutePath} {schemas.map(_.absolutePath).mkString(" ")}</x> ! log
    } catch { case e: Exception =>
      throw new RuntimeException("error occured while compiling zerocice files: %s" format(e.getMessage), e)
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

  private def sourceGeneratorTask =
    (streams,
     sourceDirectory in slice2java,
     excludeFilter in slice2java,
     javaSource in slice2java,
     includePaths in slice2java,
     stream in slice2java,
     underscore in slice2java,
     checksum in slice2java,
     cacheDirectory in slice2java,
     slice2javaBin in slice2java) map {
    (out, srcDir, excludeFilter, targetDir, includePaths, stream, underscore,
     checksum, cache, slice2javaCommand) =>
      val cachedCompile = FileFunction.cached(
        cache / "zerocice",
        inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) {
        (in: Set[File]) =>
          compile(slice2javaCommand, srcDir, excludeFilter, targetDir,
                  includePaths, stream, underscore, checksum, out.log)
      }
      val srcs = srcDir.descendantsExcept("*.ice", excludeFilter).get.toSet
      cachedCompile(srcs).toSeq
  }

  private def unpackDependenciesTask = (streams, managedClasspath in slice2java, externalIncludePath in slice2java) map {
    (out, deps, extractTarget) =>
      val extractedFiles = unpack(deps.map(_.data), extractTarget, out.log)
      UnpackedDependencies(extractTarget, extractedFiles)
  }

}
