// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.interop

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import dagger.internal.codegen.KspComponentProcessor
import dev.zacsweers.metro.compiler.MetroDirectives
import dev.zacsweers.metro.compiler.test.JVM_TARGET
import io.github.classgraph.ClassGraph
import java.io.File
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.AdditionalSourceProvider
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.getOrCreateTempDirectory
import org.jetbrains.kotlin.test.services.isJavaFile
import org.jetbrains.kotlin.test.services.isKtFile

class Ksp2AdditionalSourceProvider(testServices: TestServices) :
  AdditionalSourceProvider(testServices) {
  override fun produceAdditionalFiles(
    globalDirectives: RegisteredDirectives,
    module: TestModule,
    testModuleStructure: TestModuleStructure,
  ): List<TestFile> {
    val providers = buildList {
      if (MetroDirectives.enableDaggerKsp(module.directives)) {
        add(KspComponentProcessor.Provider())
      }
    }
    if (providers.isEmpty()) return emptyList()

    // Write out test files to KSP input directories
    val kotlinInput = testServices.getOrCreateTempDirectory("ksp-kotlin-input-${module.name}")
    val javaInput = testServices.getOrCreateTempDirectory("ksp-java-input-${module.name}")

    for (testFile in module.files) {
      val directory =
        when {
          testFile.isKtFile -> kotlinInput
          testFile.isJavaFile -> javaInput
          else -> continue
        }

      val path = directory.resolve(testFile.relativePath)
      path.parentFile.mkdirs()
      // TODO this escapes other preprocessors but
      //  testServices.sourceFileProvider.getContentOfSourceFile calls testServices.moduleStructure
      //  before it's available in 2.3.x
      path.writeText(testFile.originalContent)
      // path.writeText(testServices.sourceFileProvider.getContentOfSourceFile(testFile))
    }

    // Setup KSP output directories
    val projectBase = testServices.getOrCreateTempDirectory("ksp-project-base-${module.name}")
    val caches = projectBase.resolve("caches").also { it.mkdirs() }
    val classOutput = projectBase.resolve("classes").also { it.mkdirs() }
    val outputBase = projectBase.resolve("sources").also { it.mkdirs() }
    val kotlinOutput = outputBase.resolve("kotlin").also { it.mkdirs() }
    val javaOutput = outputBase.resolve("java").also { it.mkdirs() }
    val resourceOutput = outputBase.resolve("resources").also { it.mkdirs() }

    val config =
      KSPJvmConfig.Builder()
        .apply {
          jvmTarget = JVM_TARGET
          jdkHome = File(System.getProperty("java.home"))
          languageVersion = module.languageVersionSettings.languageVersion.versionString
          apiVersion = module.languageVersionSettings.apiVersion.versionString
          allWarningsAsErrors = true // TODO is there a corresponding Directive?

          moduleName = module.name
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
        }
        .build()

    val messageCollector =
      PrintingMessageCollector(System.err, MessageRenderer.PLAIN_RELATIVE_PATHS, true)
    val logger = TestKSPLogger(messageCollector, allWarningsAsErrors = config.allWarningsAsErrors)
    try {
      when (KotlinSymbolProcessing(config, providers, logger).execute()) {
        KotlinSymbolProcessing.ExitCode.PROCESSING_ERROR -> error("Processing error!")
        KotlinSymbolProcessing.ExitCode.OK -> {
          // Succeeded
        }
      }
    } finally {
      logger.reportAll()
    }

    val kotlinKspTestFiles =
      kotlinOutput.walkTopDown().filter { it.isFile }.map { it.toTestFile() }.toList()
    val javaKspTestFiles =
      javaOutput.walkTopDown().filter { it.isFile }.map { it.toTestFile() }.toList()
    return kotlinKspTestFiles + javaKspTestFiles
  }

  // TODO remove this in favor of explicit Gradle configuration?
  //  - or is there a way to extract the classpath from the test framework?
  private fun getHostClasspaths(): List<File> {
    val classGraph = ClassGraph().enableSystemJarsAndModules().removeTemporaryFilesAfterScan()

    val classpaths = classGraph.classpathFiles
    val modules = classGraph.modules.mapNotNull { it.locationFile }

    return (classpaths + modules).distinctBy(File::getAbsolutePath)
  }
}
