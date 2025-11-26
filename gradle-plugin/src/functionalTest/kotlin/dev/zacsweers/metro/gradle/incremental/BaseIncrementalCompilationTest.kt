// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.Subproject
import dev.zacsweers.metro.gradle.copy
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
    metroDir.resolve(compilation).let(::Reports)

  protected val MetroGradleProject.mainReports: Reports
    get() = reports("main")

  protected val MetroGradleProject.appGraphReports: GraphReports
    get() = mainReports.forGraph("AppGraph")

  class Reports(val dir: File) {
    fun forGraph(graph: String): GraphReports {
      return GraphReports(dir, graph)
    }
  }

  class GraphReports(val reportsDir: File, val name: String) {
    val keysPopulated: Set<String> by lazy {
      reportsDir.resolve("keys-populated-$name.txt").readLines().toSet()
    }
    val providerPropertyKeys: Set<String> by lazy {
      reportsDir.resolve("keys-providerProperties-$name.txt").readLines().toSet()
    }
    val scopedProviderPropertyKeys: Set<String> by lazy {
      reportsDir.resolve("keys-scopedProviderProperties-$name.txt").readLines().toSet()
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
  ) {
    val newSource = source.copy(content)
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
