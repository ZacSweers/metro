// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("dev.zacsweers.metro")
}

kotlin {
  jvm()

  js { browser() }
  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

  configureOrCreateNativePlatforms()

  sourceSets {
    commonMain { dependencies { implementation(project(":runtime")) } }
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        // For Platform use
        implementation(libs.ktor.client)
      }
    }
  }
}

metro { debug.set(false) }

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
}

configurations.configureEach {
  resolutionStrategy.dependencySubstitution {
    substitute(module("dev.zacsweers.metro:runtime")).using(project(":runtime"))
    substitute(module("dev.zacsweers.metro:runtime-jvm")).using(project(":runtime"))
    substitute(module("dev.zacsweers.metro:compiler")).using(project(":compiler"))
  }
}

// Sourced from https://kotlinlang.org/docs/native-target-support.html
fun KotlinMultiplatformExtension.configureOrCreateNativePlatforms() {
  // Tier 1
  linuxX64()
  macosX64()
  macosArm64()
  iosSimulatorArm64()
  iosX64()

  // Tier 2
  linuxArm64()
  watchosSimulatorArm64()
  watchosX64()
  watchosArm32()
  watchosArm64()
  tvosSimulatorArm64()
  tvosX64()
  tvosArm64()
  iosArm64()

  // Tier 3
  androidNativeArm32()
  androidNativeArm64()
  androidNativeX86()
  androidNativeX64()
  mingwX64()
  watchosDeviceArm64()
}
