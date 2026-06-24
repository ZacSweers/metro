// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuntimeTracingConfigurationTest {
  @Test
  fun `enableRuntimeTracing adds metro-trace to jvm runtime classpath`() {
    val project = runtimeTracingProject(enableRuntimeTracing = true).gradleProject

    val result = build(project.rootDir, "dependencies", "--configuration", "runtimeClasspath")

    assertThat(result.output).contains("dev.zacsweers.metro:metro-trace")
  }

  @Test
  fun `runtime tracing disabled does not add metro-trace to jvm runtime classpath`() {
    val project = runtimeTracingProject(enableRuntimeTracing = false).gradleProject

    val result = build(project.rootDir, "dependencies", "--configuration", "runtimeClasspath")

    assertThat(result.output).doesNotContain("dev.zacsweers.metro:metro-trace")
  }

  private fun runtimeTracingProject(enableRuntimeTracing: Boolean): MetroProject {
    return object : MetroProject(multiplatform = false) {
      override fun sources() =
        listOf(
          source(
            """
            @DependencyGraph
            interface AppGraph {
              @Provides fun provideString(): String = "string"
            }
            """,
            "AppGraph",
          )
        )

      override fun StringBuilder.onBuildScript() {
        appendLine("metro {")
        appendLine("  enableRuntimeTracing.set($enableRuntimeTracing)")
        appendLine("}")
      }
    }
  }
}
