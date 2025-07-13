// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.AbstractGradleProject
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.Source

abstract class MetroProject(
  private val debug: Boolean = false,
  private val metroOptions: MetroOptionOverrides = MetroOptionOverrides(),
) : AbstractGradleProject() {
  protected abstract fun sources(): List<Source>

  open fun StringBuilder.onBuildScript() {}

  val gradleProject: GradleProject
    get() =
      newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          sources = this@MetroProject.sources()
          withBuildScript {
            plugins(GradlePlugins.Kotlin.jvm, GradlePlugins.metro)

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
                val metroOptions =
                  metroOptions.enableScopedInjectClassHints
                    ?.let { "enableScopedInjectClassHints.set($it)" }
                    .orEmpty()
                if (metroOptions.isNotBlank()) {
                  appendLine("  $metroOptions")
                }
                appendLine("}")
              }
            )
          }
        }
        .write()
}
