import sbt.internal.inc
import sbt.internal.inc._
import sbt.internal.util.ConsoleLogger
import xsbti.VirtualFile
import xsbti.compile.{FileAnalysisStore, ScalaInstance, _}

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.EnumerationHasAsScala
import scala.sys.process._

object Main {

  val sourceDir = new File("example/src").getAbsoluteFile
  val javaFile = new File("example/src/Person.java").getAbsoluteFile
  val builtJava = new File("output/java").getAbsoluteFile
  val builtScala = new File("output/scala").getAbsoluteFile
  val cacheDir = new File("zinc-cache").getAbsoluteFile
  val zincVersion = System.getenv("ZINC_VERSION")

  val javaSource = """public interface Person { String getName(); }"""
  val updatedJavaSource = """public interface Person { String fooBar(); }"""

  def main(args: Array[String]): Unit = {
    println(s"zinc version: $zincVersion")

    val scalaSources = sourceDir.listFiles().filter(_.getName.endsWith(".scala"))
    val scalaLibrary = locateJar("scala-library")

    val scalaClasspath = Array(scalaLibrary, builtJava)

    args.headOption match {
      case Some("first") => firstRun(scalaSources, scalaClasspath)
      case Some("second") => secondRun(scalaSources, scalaClasspath)
      case _ =>
        firstRun(scalaSources, scalaClasspath)
        println(s"Sleeping for 3 seconds just in case something depends on the exact timestamp, ${LocalDateTime.now()}")
        Thread.sleep(3000)
        secondRun(scalaSources, scalaClasspath)
    }
  }

  def firstRun(scalaSources: Array[File], scalaClasspath: Array[File]): Unit = {
    if (!sourceDir.exists() || !sourceDir.isDirectory) {
      println(s"Source directory does not exist or is not a directory: ${sourceDir.getAbsolutePath}")
      System.exit(1)
    }
    javaFile.delete()
    deleteRecursively(builtScala)
    deleteRecursively(builtJava)
    deleteRecursively(cacheDir)
    builtScala.mkdirs()
    builtJava.mkdirs()
    cacheDir.mkdirs()

    println("\n\nCreating java file")
    writeFile(javaFile, javaSource)

    println("Compiling java for the 1st time")
    compileJavaFiles(Array(javaFile))

    println("\n\nCompiling scala for the 1st time")
    compileScala(sources = scalaSources, classpath = scalaClasspath, outputDir = builtScala)
  }

  def secondRun(scalaSources: Array[File], scalaClasspath: Array[File]): Unit = {
    println("\n\nChanging the source code for java")
    writeFile(javaFile, updatedJavaSource)
    println("Re-compiling java")
    compileJavaFiles(Array(javaFile))

    println("\n\nRe-compiling scala, should fail")
    try {
      compileScala(sources = scalaSources, classpath = scalaClasspath, outputDir = builtScala)
      System.err.println("should fail, but it didn't")
    } catch {
      case _: Throwable => println("Failed, as expected")
    }
  }

  def writeFile(file: File, text: String): Unit = {
    val writer = new java.io.PrintWriter(file)
    try {
      writer.write(text)
    } finally {
      writer.close()
    }
  }

  def compileJavaFiles(javaSources: Array[File]): Unit = {
    val compileCommand = s"javac -source 11 -target 11 -XDuseUnsharedTable=true -g -proc:none -d '$builtJava' ${javaSources.mkString(" ")}"

    val exitCode = compileCommand.!
    if (exitCode == 0) {
      println(s"Compilation succeeded for java")
    } else {
      throw new RuntimeException(s"Compilation failed for java with exit code: $exitCode")
    }
  }

  def compileScala(sources: Array[File], classpath: Array[File], outputDir: File): Unit = {
    val logger = ConsoleLogger()
    val reporter = new LoggedReporter(100, logger)

    val scalaInstance = findScalaInstance()
    val scalaCompiler = getCompiler(scalaInstance)

    val incremental: IncrementalCompilerImpl = new IncrementalCompilerImpl
    val compilers: Compilers = incremental.compilers(
      scalaInstance,
      ClasspathOptionsUtil.boot,
      Some(Path.of(System.getProperty("java.home"))),
      scalaCompiler
    )

    val scalacOptions = Array("-deprecation", "-unchecked", "-target:jvm-1.8")
    val javacOptions = Array("-source", "11", "-target", "11", "-XDuseUnsharedTable=true", "-d", builtScala.getAbsolutePath, "-g", "-proc:none")

    println(s"Scalac options: ${scalacOptions.mkString("Array(", ", ", ")")}")
    println(s"Javac options: ${javacOptions.mkString("Array(", ", ", ")")}")

    val classpathVF = classpath.map(toVirtualFile)
    println("Classpath:\n" + (classpathVF.mkString("\n")))
    val sourcesVF = sources.map(toVirtualFile)
    println("Sources:\n" + (sourcesVF.mkString("\n")))

    val compileOptions: CompileOptions = CompileOptions.create()
      .withSources(sourcesVF)
      .withClasspath(classpathVF)
      .withScalacOptions(scalacOptions)
      .withClassesDirectory(builtScala.toPath)
      .withJavacOptions(javacOptions)

    val analysisFile: File = new File(cacheDir, s"compileScala.analysis").getAbsoluteFile
    println(s"Analysis file: $analysisFile, exists: ${analysisFile.exists()}")
    val classFileManagerType = TransactionalManagerType.of(new File(cacheDir, s"classfiles.bak").getAbsoluteFile, logger)

    val analysisStoreProvider = new AnalysisStoreProvider
    val analysisStore = analysisStoreProvider.get(analysisFile)

    val previousResult: PreviousResult = analysisStore.get
      .map[PreviousResult]((contents: AnalysisContents) => PreviousResult.of(Optional.of(contents.getAnalysis), Optional.of(contents.getMiniSetup)))
      .orElse(PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup]))

    println(s"Prev result $previousResult\n\n " +
      s"compilations: ${previousResult.analysis.map((a: CompileAnalysis) => a.readCompilations.getAllCompilations.mkString(","))}\n\n" +
      s"library stamps: ${previousResult.analysis.map((a: CompileAnalysis) => a.readStamps().getAllLibraryStamps)}\n\n" +
      s"product stamps: ${previousResult.analysis.map((a: CompileAnalysis) => a.readStamps().getAllProductStamps)}\n\n" +
      s"source stamps: ${previousResult.analysis.map((a: CompileAnalysis) => a.readStamps().getAllSourceStamps)}\n\n" +
      s"source info: ${previousResult.analysis().map((a: CompileAnalysis) => a.readSourceInfos().getAllSourceInfos)}\n\n" +
      s"setup ${previousResult.setup().map((s: MiniSetup) => s"[${s.extra().mkString(", ")}], [${s.options().javacOptions().mkString(", ")}], ${s.options().scalacOptions().mkString}\nCP hash: ${s.options().classpathHash().mkString("\n")}")}\n"
    )

    val incOptions: IncOptions = IncOptions.of()
      .withRecompileOnMacroDef(false)
      .withClassfileManagerType(classFileManagerType)
      .withTransitiveStep(5)

    val setup: Setup = incremental.setup(
      lookup = new EntryLookup(builtScala, analysisFile, analysisStoreProvider),
      skip = false,
      cacheFile = analysisFile.toPath,
      cache = CompilerCache.fresh,
      incOptions = incOptions, // MappedPosition is used to make sure toString returns proper error messages
      reporter = reporter,
      progress = Option.empty,
      earlyAnalysisStore = Option.empty,
      extra = Array.empty
    )

    val inputs: Inputs = incremental.inputs(compileOptions, compilers, setup, previousResult)

    val compileResult = incremental.compile(inputs, logger)
    val contentNext = AnalysisContents.create(compileResult.analysis, compileResult.setup)
    analysisStore.set(contentNext)
    println(
      s"compiled setup: ${Optional.of(compileResult.setup).map((s: MiniSetup) => s"[${s.extra().mkString(", ")}], [${s.options().javacOptions().mkString(", ")}], ${s.options().scalacOptions().mkString}\nCP hash: ${s.options().classpathHash().mkString("\n")}")}\n"
    )
    reporter.printSummary()
  }

  /**
   * Helper method to locate the Scala library and compiler JARs.
   */
  def findScalaInstance(): ScalaInstance = {
    val scalaLibrary = locateJar("scala-library")
    val scalaCompiler = locateJar("scala-compiler")
    val scalaCompilerInterface = locateJar("compiler-interface")
    val scalaReflect = locateJar("scala-reflect")

    val classLoader =  new URLClassLoader(
      Array(
        scalaLibrary.toURI.toURL,
        scalaReflect.toURI.toURL,
        scalaCompiler.toURI.toURL,
      )
    )
    val libraryClassLoader = new URLClassLoader(
      Array(
        scalaLibrary.toURI.toURL,
        scalaReflect.toURI.toURL
      )
    )
    val compilerClassLoader = new URLClassLoader(
      Array(
        scalaCompiler.toURI.toURL,
        scalaReflect.toURI.toURL
      )
    )

    new inc.ScalaInstance(
      version = "2.13",
      loader = classLoader,
      loaderCompilerOnly = compilerClassLoader,
      loaderLibraryOnly = libraryClassLoader,
      libraryJars = Array(scalaLibrary, scalaReflect),
      compilerJars = Array(scalaCompiler, scalaReflect),
      allJars = Array[File](scalaLibrary, scalaCompiler, scalaReflect, scalaCompilerInterface),
      explicitActual = Option.empty
    );
  }

  private def getCompiler(scalaInstance: ScalaInstance): AnalyzingCompiler = {
    val compilerBridgeJar = locateJar(s"compiler-bridge_${scalaInstance.version().split('.').take(2).mkString(".")}-$zincVersion.jar")
    new AnalyzingCompiler(
      scalaInstance = scalaInstance,
      provider = ZincUtil.constantBridgeProvider(scalaInstance, compilerBridgeJar),
      classpathOptions = ClasspathOptionsUtil.manual(),
      onArgsHandler = args => println(s"Compiler args: ${args.mkString(" ")}"),
      classLoaderCache = None
    )
  }

  /**
   * Locates a JAR file by name in the classpath.
   */
  private def locateJar(jarName: String): File = {
    val resource = this.getClass.getClassLoader.getResources("").asScala
      .find(_.getPath.contains(jarName))
      .map(url => new File(url.toURI))

    resource match {
      case Some(resource) =>
        println(s"located $jarName via classloader at ${resource.getAbsolutePath}")
        resource
      case None =>
        val classpathElements = System.getProperty("java.class.path").split(File.pathSeparator)
        val result = classpathElements
          .find(_.contains(jarName))
          .map(new File(_))
          .getOrElse(throw new RuntimeException(s"Could not find $jarName in classpath."))
        println(s"located $jarName via java.class.path at ${result.getAbsolutePath}")
        result
    }
  }

  private class EntryLookup(
     destinationDir: File,
     analysisFile: File,
     private val analysisStoreProvider: AnalysisStoreProvider,
   ) extends PerClasspathEntryLookup {

    private val analysisMap: Map[VirtualFile, File] = Map[VirtualFile, File](
      toVirtualFile(destinationDir) -> analysisFile,
    )

    override def analysis(classpathEntry: VirtualFile): Optional[CompileAnalysis] = {
      analysisMap.get(classpathEntry) match {
        case None => Optional.empty()
        case Some(file) =>
          analysisStoreProvider.get(file).get.map[CompileAnalysis](_.getAnalysis)
      }
    }

    override def definesClass(classpathEntry: VirtualFile): DefinesClass = {
      analysis(classpathEntry)
        .map[DefinesClass]((a: CompileAnalysis) => a match {
          case analysis: Analysis => new AnalysisBakedDefineClass(analysis)
          case _ => null
        })
        .orElseGet(() => Locate.definesClass(classpathEntry))
    }
  }

  private class AnalysisBakedDefineClass(private val analysis: Analysis) extends DefinesClass {
    override def apply(className: String): Boolean = analysis.relations.productClassName.reverse(className).nonEmpty
  }

  private class AnalysisStoreProvider {
    final private val cache = new ConcurrentHashMap[File, AnalysisStore]

    def get(analysisFile: File): AnalysisStore = {
      FileAnalysisStore.getDefault(analysisFile)
      //      AnalysisStore.getCachedStore(
      //        cache.computeIfAbsent(analysisFile, f => AnalysisStore.getThreadSafeStore(FileAnalysisStore.getDefault(f)))
      //      )
    }
  }

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      Option(file.listFiles).foreach(_.foreach(deleteRecursively))
    }
    if (file.exists && !file.delete()) {
      throw new Exception(s"Unable to delete ${file.getAbsolutePath}")
    }
  }

  private def toVirtualFile(file: File) = PlainVirtualFileConverter.converter.toVirtualFile(file.getAbsoluteFile.toPath)
}
