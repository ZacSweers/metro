// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.supportsTopLevelFirGen
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContributionHintICTests :
  BaseIncrementalCompilationTest(
    target = KmpTarget.JVM,
    requiresMultiplatformIc = false,
  ) {

  @Test
  fun contributionScopeArgumentChangeRemovesOldIrHint() {
    val selectedTarget = System.getProperty("metro.functionalTestKmpTarget")
    assumeTrue(selectedTarget == null || selectedTarget == "jvm")
    assumeFalse(
      "IR contribution hints are only supported before FIR contribution hint generation became required",
      getTestCompilerToolingVersion().supportsTopLevelFirGen(),
    )

    val fixture =
      object : MetroProject(multiplatform = false) {
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
            class RetainedScope

            @BindingContainer
            @ContributesTo(AppScope::class)
            @ContributesTo(RetainedScope::class)
            class StringModule {
              @Provides fun provideString(): String = "test"
            }
            """
              .trimIndent()
          )

        val changedContribution =
          """
          class AnotherScope
          class RetainedScope

          @BindingContainer
          @ContributesTo(AnotherScope::class)
          @ContributesTo(RetainedScope::class)
          class StringModule {
            @Provides fun provideString(): String = "test"
          }
          """
            .trimIndent()

        val retainedGraph =
          source(
            """
            @DependencyGraph(RetainedScope::class)
            interface RetainedGraph {
              val string: String
            }
            """
              .trimIndent()
          )

        override fun StringBuilder.onBuildScript() {
          appendLine(
            """
            @OptIn(
              dev.zacsweers.metro.gradle.DelicateMetroGradleApi::class,
              dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class,
            )
            metro {
              generateContributionHintsInFir.set(false)
            }
            """
              .trimIndent()
          )
        }

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(bindingContainer) }
          subproject("retained") {
            sources(retainedGraph)
            dependencies(Dependency.implementation(":lib"))
          }
        }
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    val firstBuild = project.compileKotlin(task = ":compileKotlin")
    assertThat(firstBuild.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val firstRetainedBuild = project.compileKotlin(task = ":retained:compileKotlin")
    assertThat(firstRetainedBuild.task(":retained:compileKotlin")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    libProject.modify(
      rootDir = project.rootDir,
      source = fixture.bindingContainer,
      content = fixture.changedContribution,
      sourceSet = "main",
    )

    val secondBuild = project.compileKotlinAndFail(task = ":compileKotlin")
    assertThat(secondBuild.output).contains("[Metro/MissingBinding]")
    val secondRetainedBuild = project.compileKotlin(task = ":retained:compileKotlin")
    assertThat(secondRetainedBuild.task(":retained:compileKotlin")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
  }
}
