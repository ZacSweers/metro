// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

class MirrorRemovalBoundaryTests :
  BaseIncrementalCompilationTest(
    target = KmpTarget.JVM,
    requiresMultiplatformIc = false,
  ) {

  @Test
  fun contributionScopeArgumentChangeIsDetected() {
    val fixture =
      object :
        MetroProject(
          metroOptions = MetroOptionOverrides(omitRedundantMirrors = true),
          multiplatform = false,
        ) {
        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val target: Target
            }

            @Inject
            class Target(val string: String)
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            class AnotherScope

            @BindingContainer
            @ContributesTo(AppScope::class)
            class StringModule {
              @Provides fun provideString(): String = "test"
            }
            """
              .trimIndent()
          )

        val changedContribution =
          """
          class AnotherScope

          @BindingContainer
          @ContributesTo(AnotherScope::class)
          class StringModule {
            @Provides fun provideString(): String = "test"
          }
          """
            .trimIndent()

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(bindingContainer) }
        }
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    val firstBuild = project.compileKotlin(task = ":compileKotlin")
    assertThat(firstBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    libProject.modify(
      rootDir = project.rootDir,
      source = fixture.bindingContainer,
      content = fixture.changedContribution,
      sourceSet = "main",
    )

    val secondBuild = project.compileKotlinAndFail(task = ":compileKotlin")
    assertThat(secondBuild.output).contains("[Metro/MissingBinding]")
  }
}
