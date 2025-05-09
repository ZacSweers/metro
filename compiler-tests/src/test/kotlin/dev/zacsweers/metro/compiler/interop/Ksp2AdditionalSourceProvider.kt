package dev.zacsweers.metro.compiler.interop

import com.google.devtools.ksp.impl.CommandLineKSPLogger
import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import dagger.internal.codegen.KspComponentProcessor
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.AdditionalSourceProvider
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.getOrCreateTempDirectory
import org.jetbrains.kotlin.test.services.isJavaFile
import org.jetbrains.kotlin.test.services.isKtFile
import org.jetbrains.kotlin.test.services.sourceFileProvider
import org.jetbrains.kotlin.test.util.KtTestUtil
import java.io.File

class Ksp2AdditionalSourceProvider(
  testServices: TestServices,
) : AdditionalSourceProvider(testServices) {
  override fun produceAdditionalFiles(
    globalDirectives: RegisteredDirectives,
    module: TestModule,
    testModuleStructure: TestModuleStructure
  ): List<TestFile> {
    // TODO base processors on directives
    val symbolProcessorProviders = listOf(
      KspComponentProcessor.Provider()
    )

    val kotlinInput = testServices.getOrCreateTempDirectory("ksp-kotlin-input-${module.name}")
    val javaInput = testServices.getOrCreateTempDirectory("ksp-java-input-${module.name}")

    for (testFile in module.files) {
      val directory = when {
        testFile.isKtFile -> kotlinInput
        testFile.isJavaFile -> javaInput
        else -> continue
      }

      val path = directory.resolve(testFile.relativePath)
      path.parentFile.mkdirs()
      path.writeText(testServices.sourceFileProvider.getContentOfSourceFile(testFile))
    }

    val projectBase = testServices.getOrCreateTempDirectory("ksp-project-base-${module.name}")
    val caches = projectBase.resolve("caches").also { it.mkdirs() }
    val classOutput = projectBase.resolve("classes").also { it.mkdirs() }
    val outputBase = projectBase.resolve("sources").also { it.mkdirs() }
    val kotlinOutput = outputBase.resolve("kotlin").also { it.mkdirs() }
    val javaOutput = outputBase.resolve("java").also { it.mkdirs() }
    val resourceOutput = outputBase.resolve("resources").also { it.mkdirs() }

    val config = KSPJvmConfig.Builder().apply {

//          incremental = this@Ksp2PrecursorTool.incremental
//          incrementalLog = this@Ksp2PrecursorTool.incrementalLog
//          allWarningsAsErrors = this@Ksp2PrecursorTool.allWarningsAsErrors
//          processorOptions = this@Ksp2PrecursorTool.processorOptions.toMap()

      jvmTarget = "11"
      jdkHome = KtTestUtil.getJdk11Home()
      languageVersion = KotlinVersion.CURRENT.let { "${it.major}.${it.minor}" }
      apiVersion = KotlinVersion.CURRENT.let { "${it.major}.${it.minor}" }

      moduleName = "main"
      sourceRoots = listOf(kotlinInput)
      javaSourceRoots = listOf(javaInput)
      libraries = getHostClasspaths()

      projectBaseDir = projectBase
      outputBaseDir = outputBase
      cachesDir = caches
      classOutputDir = classOutput
      kotlinOutputDir = kotlinOutput
      resourceOutputDir = resourceOutput
      javaOutputDir = javaOutput
    }.build()

    // TODO collect errors and throw?
    //  - this is basically how the exit code works
    //  - error reported exit code => processing error
    val logger = CommandLineKSPLogger()
    KotlinSymbolProcessing(config, symbolProcessorProviders, logger).execute()

    val kotlinKspTestFiles = kotlinOutput.walkTopDown().filter { it.isFile }.map { it.toTestFile() }.toList()
    val javaKspTestFiles = javaOutput.walkTopDown().filter { it.isFile }.map { it.toTestFile() }.toList()
    return kotlinKspTestFiles + javaKspTestFiles
  }

  private fun getHostClasspaths(): List<File> {
    val classGraph = ClassGraph()
      .enableSystemJarsAndModules()
      .removeTemporaryFilesAfterScan()

    val classpaths = classGraph.classpathFiles
    val modules = classGraph.modules.mapNotNull { it.locationFile }

    return (classpaths + modules).distinctBy(File::getAbsolutePath)
  }
}
