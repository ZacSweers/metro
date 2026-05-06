// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

/**
 * Targets a parameterized [MetroProject] is exercised against. Each entry maps to a single Kotlin
 * multiplatform target declared by [MetroProject.applyMetroDefault] when `multiplatform = true`.
 */
enum class KmpTarget(val gradleTargetName: String) {
  JVM("jvm"),
  JS("js"),
  WASM_JS("wasmJs"),
  IOS_SIMULATOR_ARM64("iosSimulatorArm64");

  /** The Gradle compile task for this target's main compilation, e.g. `compileKotlinJvm`. */
  val compileTaskName: String =
    "compileKotlin" + gradleTargetName.replaceFirstChar { it.titlecase() }

  override fun toString(): String = gradleTargetName
}
