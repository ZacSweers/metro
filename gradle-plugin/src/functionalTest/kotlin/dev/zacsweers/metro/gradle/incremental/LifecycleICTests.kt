// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.invokeMain
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

class LifecycleICTests :
  BaseIncrementalCompilationTest(KmpTarget.JVM, requiresMultiplatformIc = false) {
  @Test
  fun contributedCallbacksAreAddedChangedAndRemovedIncrementally() {
    val fixture =
      object :
        MetroProject(
          multiplatform = false,
          additionalGradleProperties = listOf("kotlin.build.report.output=file"),
        ) {
        val contribution = source(contributionContent(""), "LifecycleContribution")
        val graph =
          source(
            """
        @OptIn(ExperimentalMetroApi::class)
        @DependencyGraph(LifecycleScope::class)
        interface AppGraph : CloseableGraph

        @OptIn(ExperimentalMetroApi::class)
        fun main(): String {
          State.events.clear()
          val graph = createGraph<AppGraph>()
          graph.close()
          return State.events.joinToString()
        }
        """,
            "Main",
          )

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(graph)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(contribution) }
        }
      }
    val project = fixture.gradleProject
    val contributionFile =
      project.rootDir.resolve("lib/src/main/kotlin/test/LifecycleContribution.kt")

    fun verify(expected: String) {
      val before =
        project.rootDir.resolve("build/reports/kotlin-build").listFiles().orEmpty().toSet()
      val result = project.compileKotlin(task = ":compileKotlin")
      assertThat(result.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      val reports =
        project.rootDir.resolve("build/reports/kotlin-build").listFiles().orEmpty().toSet()
      val report = (reports - before).single()
      if (before.isNotEmpty()) {
        assertThat(compiledSourceNames(report, ":compileKotlin")).contains("Main.kt")
      }
      assertThat(project.invokeMain<String>(target = null)).isEqualTo(expected)
    }

    fun replaceCallbacks(callbacks: String) {
      contributionFile.writeText(
        "package test\nimport dev.zacsweers.metro.*\n" + contributionContent(callbacks),
      )
    }

    verify("")
    replaceCallbacks("@OnGraphInit fun initialize() { State.events += \"added\" }")
    verify("added")
    replaceCallbacks("@OnGraphClose fun finish() { State.events += \"changed\" }")
    verify("changed")
    replaceCallbacks("")
    verify("")
  }

  private fun contributionContent(callbacks: String): String =
    """
    object LifecycleScope
    object State { val events = mutableListOf<String>() }

    @OptIn(ExperimentalMetroApi::class)
    @ContributesTo(LifecycleScope::class)
    interface LifecycleContribution {
      $callbacks
    }
    """
      .trimIndent()
}
