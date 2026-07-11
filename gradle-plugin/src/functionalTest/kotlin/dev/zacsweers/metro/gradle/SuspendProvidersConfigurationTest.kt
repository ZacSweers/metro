// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SuspendProvidersConfigurationTest {
  @Test
  fun `enableSuspendProviders adds runtime-coroutines`() {
    val project = suspendProvidersProject(enableSuspendProviders = true).gradleProject

    val result = build(project.rootDir, "dependencies", "--configuration", "runtimeClasspath")

    assertThat(result.output).contains("dev.zacsweers.metro:runtime-coroutines")
  }

  @Test
  fun `suspend providers disabled does not add runtime-coroutines`() {
    val project = suspendProvidersProject(enableSuspendProviders = false).gradleProject

    val result = build(project.rootDir, "dependencies", "--configuration", "runtimeClasspath")

    assertThat(result.output).doesNotContain("dev.zacsweers.metro:runtime-coroutines")
  }

  private fun suspendProvidersProject(enableSuspendProviders: Boolean): MetroProject {
    return object : MetroProject(multiplatform = false) {
      override fun StringBuilder.onBuildScript() {
        appendLine("metro {")
        appendLine("  enableSuspendProviders.set($enableSuspendProviders)")
        appendLine("}")
      }
    }
  }
}
