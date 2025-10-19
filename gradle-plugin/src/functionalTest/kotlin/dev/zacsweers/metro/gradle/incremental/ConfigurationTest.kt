// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigurationTest {
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
