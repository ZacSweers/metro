// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.classLoader
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

/**
 * Verifies that graphs pick up changes to Hilt's `@AggregatedDeps` markers in upstream modules.
 *
 * The upstream module holds hand-written markers and stubs for Hilt's annotation and component, so
 * it doesn't need Hilt. Only the consumer applies Metro with Hilt interop.
 *
 * Markers added upstream aren't covered. Each marker is named after its own module or entry point,
 * and Kotlin's incremental compilation can't see a new class whose name no file looked up.
 */
class HiltAggregatedDepsICTests :
  BaseIncrementalCompilationTest(KmpTarget.JVM, requiresMultiplatformIc = false) {

  @Test
  fun removedMarkerIsDropped() {
    val project = HiltMarkersProject().gradleProject
    compileAndAssertMerged(project, "test.InitialEntryPoint")

    // The entry point stays. Only its marker goes away.
    project.libSource("hilt_aggregated_deps/_test_InitialEntryPoint.kt").delete()

    compileAndAssertMerged(project)
  }

  private fun compileAndAssertMerged(project: GradleProject, vararg expected: String) {
    val result = project.compileKotlin(task = ":compileKotlin")
    assertThat(result.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val merged =
      project
        .classLoader(target = null)
        .loadClass("test.AppGraphKt")
        .getMethod("mergedEntryPoints")
        .invoke(null)
    assertThat(merged).isEqualTo(expected.toList())
  }

  private fun GradleProject.libSource(path: String) = rootDir.resolve("lib/src/main/kotlin/$path")

  private class HiltMarkersProject : MetroProject(multiplatform = false, reportsEnabled = false) {
    override fun StringBuilder.onBuildScript() {
      appendLine(
        """
        metro {
          if (path == ":") {
            interop { includeHilt() }
          } else {
            enabled.set(false)
          }
        }
        """
          .trimIndent(),
      )
    }

    override fun buildGradleProject() = multiModuleProject {
      root {
        dependencies(Dependency.implementation(":lib"))
        sources(
          source(
            """
            @DependencyGraph(Singleton::class)
            interface AppGraph

            /** Returns the entry points that the graph implements. */
            fun mergedEntryPoints(): List<String> {
              val names = mutableSetOf<String>()
              fun visit(type: Class<*>) {
                if (!names.add(type.name)) return
                type.interfaces.forEach(::visit)
                type.superclass?.let(::visit)
              }
              visit(createGraph<AppGraph>().javaClass)
              return names.filter { it.endsWith("EntryPoint") }.sorted()
            }
            """,
            "AppGraph",
            extraImports = arrayOf("javax.inject.Singleton"),
          ),
        )
      }
      subproject("lib") {
        sources(
          source(
            """
            @Retention(AnnotationRetention.BINARY)
            annotation class AggregatedDeps(
              val components: Array<String>,
              val test: String = "",
              val replaces: Array<String> = [],
              val modules: Array<String> = [],
              val entryPoints: Array<String> = [],
            )
            """,
            fileNameWithoutExtension = "AggregatedDeps",
            packageName = "dagger.hilt.processor.internal.aggregateddeps",
            includeDefaultImports = false,
          ),
          source(
            "interface SingletonComponent",
            fileNameWithoutExtension = "SingletonComponent",
            packageName = "dagger.hilt.components",
            includeDefaultImports = false,
          ),
          source(
            "interface InitialEntryPoint",
            fileNameWithoutExtension = "InitialEntryPoint",
            includeDefaultImports = false,
          ),
          source(
            """
            @AggregatedDeps(
              components = ["dagger.hilt.components.SingletonComponent"],
              entryPoints = ["test.InitialEntryPoint"],
            )
            class _test_InitialEntryPoint
            """,
            fileNameWithoutExtension = "_test_InitialEntryPoint",
            packageName = "hilt_aggregated_deps",
            includeDefaultImports = false,
            extraImports = arrayOf("dagger.hilt.processor.internal.aggregateddeps.AggregatedDeps"),
          ),
        )
      }
    }
  }
}
