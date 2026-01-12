// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.Subproject
import dev.zacsweers.metro.gradle.copy
import dev.zacsweers.metro.gradle.resolveSafe
import java.io.File
import org.intellij.lang.annotations.Language

abstract class BaseIncrementalCompilationTest {

  protected val GradleProject.asMetroProject: MetroGradleProject
    get() = MetroGradleProject(rootDir)

  protected fun GradleProject.metroProject(path: String): MetroGradleProject {
    return MetroGradleProject(rootDir.resolve(path))
  }

  @JvmInline protected value class MetroGradleProject(val rootDir: File)

  protected val MetroGradleProject.buildDir: File
    get() = rootDir.resolve("build")

  protected val MetroGradleProject.metroDir: File
    get() = buildDir.resolve("metro")

  protected fun MetroGradleProject.reports(compilation: String): Reports =
    metroDir.resolveSafe(compilation).let(::Reports)

  protected val MetroGradleProject.mainReports: Reports
    get() = reports("main")

  protected val MetroGradleProject.appGraphReports: GraphReports
    get() = mainReports.forGraph("AppGraph", "test_AppGraph_Impl")

  class Reports(val dir: File) {
    val expectActualReports
      get() = dir.resolveSafe("expectActualReports.csv").readText()

    val lookups
      get() = dir.resolveSafe("lookups.csv").readText()

    val traceLog
      get() = dir.resolveSafe("traceLog.txt").readText()

    val log
      get() = dir.resolveSafe("log.txt").readText()

    val timings
      get() = dir.resolveSafe("timings.csv").readText()

    fun irHintsForScope(scopeFqName: String): String {
      return dir.resolveSafe("discovered-hints-ir-$scopeFqName.txt").readText()
    }

    fun firHintsForScope(scopeFqName: String): String {
      return dir.resolveSafe("discovered-hints-fir-$scopeFqName.txt").readText()
    }

    fun forGraph(simpleName: String, implFqName: String): GraphReports {
      return GraphReports(dir, simpleName, implFqName)
    }
  }

  // TODO shared model?
  class GraphReports(val reportsDir: File, val simpleName: String, val implFqName: String) {
    private fun readFileLines(path: String, extension: String = "txt"): List<String> {
      return reportsDir.resolveSafe("$path.$extension").readLines()
    }

    private fun readFile(pathWithExtension: String): String {
      return reportsDir.resolveSafe(pathWithExtension).readText()
    }

    val keysPopulated
      get() = readFileLines("keys-populated-$simpleName")

    val providerPropertyKeys
      get() = readFileLines("keys-providerProperties-$implFqName")

    val scopedProviderPropertyKeys
      get() = readFileLines("keys-scopedProviderProperties-$implFqName")

    val deferred
      get() = readFileLines("keys-deferred-$simpleName")

    val dumpKotlinLike
      get() = readFile("graph-dumpKotlin-$implFqName.kt")

    val dump
      get() = readFileLines("graph-dump-$implFqName")

    val bindingContainers
      get() = readFileLines("graph-dump-$implFqName")

    val keysValidated
      get() = readFileLines("keys-validated-$simpleName")

    val keysUnused
      get() = readFileLines("keys-unused-$simpleName")

    val metadata
      get() = readFile("graph-metadata-$implFqName.kt")

    val parentUsedKeysAll
      get() = readFile("parent-keys-used-all-$simpleName")

    fun parentKeysUsedBy(extension: String) =
      readFileLines("parent-keys-used-$simpleName-by-$extension.txt")

    fun graphMetadata() {
      // /graph-metadata/graph-test-AppGraph.json"
      // /graph-metadata/graph-test-AppGraph2.json"
      TODO()
    }
  }

  protected fun GradleProject.delete(source: Source) {
    val filePath = "src/main/kotlin/${source.path}/${source.name}.kt"
    rootDir.resolve(filePath).delete()
  }

  protected fun GradleProject.modify(source: Source, @Language("kotlin") content: String) {
    val newSource = source.copy(content)
    val filePath = "src/main/kotlin/${newSource.path}/${newSource.name}.kt"
    rootDir.resolve(filePath).writeText(newSource.source)
  }

  protected fun Subproject.modify(
    rootDir: File,
    source: Source,
    @Language("kotlin") content: String,
    includeDefaultImports: Boolean = true,
  ) {
    val newSource = source.copy(content, includeDefaultImports)
    val filePath = "src/main/kotlin/${newSource.path}/${newSource.name}.kt"
    val projectPath = rootDir.resolve(this.name.removePrefix(":").replace(":", "/"))
    projectPath.resolve(filePath).writeText(newSource.source)
  }

  protected fun Subproject.delete(rootDir: File, source: Source) {
    val filePath = "src/main/kotlin/${source.path}/${source.name}.kt"
    val projectPath = rootDir.resolve(this.name.removePrefix(":").replace(":", "/"))
    projectPath.resolve(filePath).delete()
  }

  protected fun modifyKotlinFile(
    rootDir: File,
    packageName: String,
    fileName: String,
    @Language("kotlin") content: String,
  ) {
    val packageDir = packageName.replace('.', '/')
    val filePath = "src/main/kotlin/$packageDir/$fileName"
    rootDir.resolve(filePath).writeText(content)
  }
}
