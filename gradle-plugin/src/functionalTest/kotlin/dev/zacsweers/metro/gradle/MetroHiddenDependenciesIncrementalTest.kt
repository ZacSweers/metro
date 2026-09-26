// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

/** Checks that changes upstream invalidate a consumer's cached validation without editing it. */
class MetroHiddenDependenciesIncrementalTest {

  @Test
  fun `new transitive implementation invalidates a cached clean report`() {
    val project =
      object :
          MetroProject(
            multiplatform = false,
            reportsEnabled = false,
            additionalGradleProperties =
              listOf(
                "org.gradle.configuration-cache=true",
                "org.gradle.configuration-cache.read-only=false",
              ),
          ) {
          override fun buildGradleProject() = multiModuleProject {
            root {
              dependencies(Dependency.implementation(":bridge"))
              sources(
                source(
                  """
                  @DependencyGraph(AppScope::class)
                  interface AppGraph {
                    val value: String
                    @Provides fun provideValue(): String = "original"
                  }
                  """,
                  "AppGraph",
                )
              )
            }
            subproject("bridge") {
              dependencies(Dependency.api(":scope"))
              sources(source("class Bridge"))
            }
            subproject("scope") { sources(source("abstract class AppScope")) }
            subproject("impl") {
              dependencies(Dependency.implementation(":scope"))
              sources(
                source(
                  """
                  @ContributesTo(AppScope::class)
                  interface AddedBindings
                  """,
                  "AddedBindings",
                )
              )
            }
          }
        }
        .gradleProject

    val report = project.rootDir.resolve("build/reports/metro/main/hidden-dependencies.txt")
    val checkTask = ":checkMainMetroHiddenDependencies"
    val arguments = arrayOf("--build-cache", "--isolated-projects", "--console=plain")
    val first = build(project.rootDir, ":compileKotlin", checkTask, *arguments)
    assertThat(first.task(":compileKotlin")?.outcome)
      .isAnyOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(first.task(checkTask)?.outcome).isAnyOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
    assertThat(first.task(":impl:compileKotlin")).isNull()
    assertThat(report.readText()).isEmpty()

    // Require a real build-cache hit before testing the change to runtime dependency inputs.
    assertThat(report.delete()).isTrue()
    val restored = build(project.rootDir, checkTask, *arguments)
    assertThat(restored.task(checkTask)?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    assertThat(restored.task(":compileKotlin")).isNull()
    assertThat(report.readText()).isEmpty()

    // This is the only project edit. The consumer's sources and dependencies stay identical.
    project.rootDir
      .resolve("bridge/build.gradle.kts")
      .appendText("\ndependencies { implementation(project(\":impl\")) }\n")
    val incremental = buildAndFail(project.rootDir, checkTask, *arguments)
    assertThat(incremental.task(checkTask)?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(incremental.task(":impl:compileKotlin")).isNotNull()
    assertThat(incremental.task(":compileKotlin")).isNull()
    assertThat(report.readText()).contains("project ':impl'")
    assertThat(incremental.output).contains("Hidden Metro dependencies are missing")

    // Gradle may restore configuration, but a previously failed check must still fail again.
    val repeated = buildAndFail(project.rootDir, checkTask, *arguments)
    assertThat(repeated.output).contains("Reusing configuration cache")
    assertThat(repeated.task(checkTask)?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(report.readText()).contains("project ':impl'")
  }
}
