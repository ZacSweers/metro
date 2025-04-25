package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.AbstractGradleProject.Companion.PLUGIN_UNDER_TEST_VERSION
import com.autonomousapps.kit.gradle.Plugin

object GradlePlugins {
  private val pluginVersion = PLUGIN_UNDER_TEST_VERSION

  val metro = Plugin("dev.zacsweers.metro", pluginVersion)

  object Kotlin {
    val jvm = Plugin("org.jetbrains.kotlin.jvm", "2.1.20")
  }
}
