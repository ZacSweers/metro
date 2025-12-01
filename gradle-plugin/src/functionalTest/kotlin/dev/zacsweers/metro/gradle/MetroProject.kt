// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.AbstractGradleProject
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.RootProject
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.gradle.BuildScript
import com.autonomousapps.kit.gradle.DependencyResolutionManagement
import com.autonomousapps.kit.gradle.PluginManagement
import com.autonomousapps.kit.gradle.Repositories
import com.autonomousapps.kit.gradle.Repository

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
          withMetroSettings()
        }
        .write()

  protected fun RootProject.Builder.withMetroSettings() = apply {
    withSettingsScript {
      pluginManagement = PluginManagement(metroRepositories(Repository.DEFAULT_PLUGINS))
      dependencyResolutionManagement =
        DependencyResolutionManagement(metroRepositories(Repository.DEFAULT))
    }
  }

  private fun metroRepositories(defaults: List<Repository>): Repositories =
    Repositories(
      mutableListOf<Repository>().apply {
        addAll(defaults)
        add(Repository.ofMaven("https://packages.jetbrains.team/maven/p/kt/dev/"))
        add(Repository.ofMaven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/"))
      }
    )

  /** Generates just the `metro { ... }` block content for use in custom build scripts. */
  fun buildMetroBlock(): String = buildString {
    appendLine("@OptIn(dev.zacsweers.metro.gradle.DelicateMetroGradleApi::class)")
    appendLine("metro {")
    appendLine("  debug.set($debug)")
    if (reportsEnabled) {
      appendLine("  reportsDestination.set(layout.buildDirectory.dir(\"metro\"))")
    }
    val options = buildList {
      metroOptions.enableFullBindingGraphValidation?.let {
        add("enableFullBindingGraphValidation.set($it)")
      }
    }
    if (options.isNotEmpty()) {
      options.joinTo(this, separator = "\n", prefix = "  ")
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
