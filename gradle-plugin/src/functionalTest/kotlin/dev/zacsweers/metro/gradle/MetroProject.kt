// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.AbstractGradleProject
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.gradle.BuildScript

abstract class MetroProject(
  private val debug: Boolean = false,
  private val metroOptions: MetroOptionOverrides = MetroOptionOverrides(),
  private val reportsEnabled: Boolean = true,
  private val kotlinVersion: String? = null,
) : AbstractGradleProject() {
  protected abstract fun sources(): List<Source>

  open fun StringBuilder.onBuildScript() {}

  open val gradleProject: GradleProject
    get() =
      newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          sources = this@MetroProject.sources()
          withBuildScript { applyMetroDefault() }
        }
        .write()

  /** Generates just the `metro { ... }` block content for use in custom build scripts. */
  fun buildMetroBlock(): String = buildString {
    appendLine("metro {")
    appendLine("  debug.set($debug)")
    if (reportsEnabled) {
      appendLine("  reportsDestination.set(layout.buildDirectory.dir(\"metro\"))")
    }
    with(metroOptions) {
      enableFullBindingGraphValidation?.let {
        appendLine("  enableFullBindingGraphValidation.set($it)")
      }
      generateContributionHints?.let { appendLine("  generateContributionHints.set($it)") }
      generateJvmContributionHintsInFir?.let {
        appendLine("  generateJvmContributionHintsInFir.set($it)")
      }
    }
    appendLine("}")
  }

  /** Default setup for simple JVM projects. For KMP or custom setups, override [gradleProject]. */
  fun BuildScript.Builder.applyMetroDefault() = apply {
    plugins(GradlePlugins.Kotlin.jvm(kotlinVersion), GradlePlugins.metro)

    withKotlin(
      buildString {
        onBuildScript()
        append(buildMetroBlock())
      }
    )
  }
}
