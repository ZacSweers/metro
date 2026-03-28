// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.source
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

class GenerateContributionProvidersICTests : BaseIncrementalCompilationTest() {

  /**
   * Tests that `generateContributionProviders` allows an implementation class to be `internal`
   * without causing recompilation of downstream modules when its ABI changes.
   *
   * Setup:
   * - `:common` - holds `Base` interface and `AppScope`
   * - `:lib` - holds `internal class Impl(...) : Base` with `@ContributesBinding`
   * - `:` (root) - holds the graph at AppScope and exposes an accessor for `Base`
   *
   * The test asserts that after an ABI-breaking change to `Impl` (which is internal), the root
   * project does _not_ recompile.
   */
  @Test
  fun contributionProvidersAllowInternalImplWithoutDownstreamRecompilation() {
    val fixture =
      object :
        MetroProject(metroOptions = MetroOptionOverrides(generateContributionProviders = true)) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(base) }
          subproject("lib") {
            sources(impl)
            dependencies(implementation(":common"))
          }
        }

        val base =
          source(
            """
            interface Base {
              fun value(): String
            }
            """
              .trimIndent()
          )

        val implSource =
          """
          @ContributesBinding(AppScope::class)
          @Inject
          internal class Impl : Base {
            override fun value(): String = "original"
          }
          """
            .trimIndent()

        val impl = source(implSource)

        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val base: Base
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.base.value()
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(firstBuildResult.task(":lib:compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Verify first build runs correctly
    val firstOutput = project.invokeMain<String>()
    assertThat(firstOutput).isEqualTo("original")

    // Modify the internal Impl class with an ABI change (add a public method).
    // If Impl were public, this would cause downstream recompilation because the class's ABI
    // changed. But since Impl is internal and only exposed through the generated provider,
    // the root project should not need to recompile.
    libProject.modify(
      project.rootDir,
      fixture.impl,
      """
      @ContributesBinding(AppScope::class)
      @Inject
      internal class Impl : Base {
        override fun value(): String = "modified" + newPublicMethod()
        fun newPublicMethod(): Int = 42
      }
      """
        .trimIndent(),
    )

    // Second build: lib should recompile, but root should be UP_TO_DATE
    // because Impl is internal and the contribution provider hides it
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(":lib:compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    // Verify second build still runs correctly with the modified impl
    val secondOutput = project.invokeMain<String>()
    assertThat(secondOutput).isEqualTo("modified42")
  }
}
