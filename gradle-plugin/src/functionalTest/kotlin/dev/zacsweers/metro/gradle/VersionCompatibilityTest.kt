// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Test

class VersionCompatibilityTest {
  @Test
  fun `supported kotlin version shows no warning`() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph)

        private val appGraph =
          source(
            """
          @DependencyGraph(Unit::class)
          interface AppGraph
          """
          )
      }

    val project = fixture.gradleProject

    val buildResult = build(project.rootDir, "compileKotlin", "--dry-run")
    // Should not contain version compatibility warnings
    assertThat(buildResult.output).doesNotContain("Metro")
    assertThat(buildResult.output).doesNotContain("Supported Kotlin versions")
  }

  @Test
  fun `version compatibility check can be disabled`() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph)

        private val appGraph =
          source(
            """
          @DependencyGraph(Unit::class)
          interface AppGraph
          """
          )

        override fun StringBuilder.onBuildScript() {
          appendLine("metro {")
          appendLine("  enableKotlinVersionCompatibilityChecks.set(false)")
          appendLine("}")
        }
      }

    val project = fixture.gradleProject

    val buildResult = build(project.rootDir, "compileKotlin", "--dry-run")
    // Should not contain any version warnings when disabled
    assertThat(buildResult.output).doesNotContain("Supported Kotlin versions")
  }

  @Test
  fun `kotlin version below minimum shows warning`() {
    val fixture =
      object : MetroProject(kotlinVersion = "2.0.0") {
        override fun sources() = listOf(appGraph)

        private val appGraph =
          source(
            """
          @DependencyGraph(Unit::class)
          interface AppGraph
          """
          )
      }

    val project = fixture.gradleProject

    val buildResult = build(project.rootDir, "compileKotlin", "--dry-run")
    assertThat(buildResult.output).contains("requires Kotlin")
    assertThat(buildResult.output).contains("or later")
  }

  @Ignore("I actually don't know how to test this")
  @Test
  fun `unrecognized kotlin version shows warning with supported versions`() {
    val fixture =
      object : MetroProject(kotlinVersion = "2.9.0") {
        override fun sources() = listOf(appGraph)

        private val appGraph =
          source(
            """
          @DependencyGraph(Unit::class)
          interface AppGraph
          """
          )
      }

    val project = fixture.gradleProject

    val buildResult = build(project.rootDir, "compileKotlin", "--dry-run")
    assertThat(buildResult.output).contains("supports the following Kotlin versions")
    assertThat(buildResult.output).contains("unrecognized version")
  }

  @Test
  fun `targeting language version 1_9 is an error`() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph)

        private val appGraph =
          source(
            """
          @DependencyGraph(Unit::class)
          interface AppGraph
          """
          )

        override fun StringBuilder.onBuildScript() {
          // language=kotlin
          appendLine(
            """
            kotlin {
              compilerOptions {
                languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9
              }
            }
            """
              .trimIndent()
          )
        }
      }

    val project = fixture.gradleProject

    // Build should fail correctly on a bad languageVersion
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        "Compilation task 'compileKotlin' targets language version '1.9' but Metro requires"
      )
  }
}
