// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder

internal fun Project.setMetroOptions(vararg options: Pair<String, String>) {
  val configuredOptions =
    if (options.any { (name, _) -> name == "enabled" }) {
      options.toList()
    } else {
      listOf("enabled" to "true") + options
    }
  KotlinCommonCompilerArgumentsHolder.getInstance(this).update {
    pluginOptions =
      configuredOptions.map { (name, value) -> "plugin:$PLUGIN_ID:$name=$value" }.toTypedArray()
  }
}

/** Removes the plugin arguments so tests can represent a project that does not use Metro. */
internal fun Project.clearMetroOptions() {
  KotlinCommonCompilerArgumentsHolder.getInstance(this).update { pluginOptions = null }
}
