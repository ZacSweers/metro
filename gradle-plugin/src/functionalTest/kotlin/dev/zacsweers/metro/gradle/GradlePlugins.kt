// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.AbstractGradleProject.Companion.PLUGIN_UNDER_TEST_VERSION
import com.autonomousapps.kit.gradle.Plugin

object GradlePlugins {
  private val pluginVersion = PLUGIN_UNDER_TEST_VERSION

  val metro = Plugin("dev.zacsweers.metro", pluginVersion)

  object Kotlin {
    fun jvm(version: String? = null) =
      Plugin("org.jetbrains.kotlin.jvm", version ?: getTestCompilerVersion())
  }
}
