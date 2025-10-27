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

  fun BuildScript.Builder.applyMetroDefault() = apply {
    plugins(GradlePlugins.Kotlin.jvm(kotlinVersion), GradlePlugins.metro)

    withKotlin(
      buildString {
        onBuildScript()

        // Metro config
        appendLine("metro {")
        appendLine(
          """
            debug.set($debug)
            reportsDestination.set(layout.buildDirectory.dir("metro"))
          """
            .trimIndent()
        )
        val metroOptions = buildList {
          metroOptions.enableFullBindingGraphValidation?.let {
            add("enableFullBindingGraphValidation.set($it)")
          }
        }
        if (metroOptions.isNotEmpty()) {
          metroOptions.joinTo(this, separator = "\n", prefix = "  ")
        }
        appendLine("}")
      }
    )
  }
}
