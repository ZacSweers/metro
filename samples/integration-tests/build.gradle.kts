// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.library)
  id("dev.zacsweers.metro")
}

android {
  namespace = "dev.zacsweers.metro.test.integration.android"

  buildFeatures { viewBinding = true }
}

metro {}

// TODO: Re-enable JS targets for 2.2.20 and 2.2.21
val kotlinVersion = libs.versions.kotlin.get()
val enableJsTargets = kotlinVersion != "2.2.20" && kotlinVersion != "2.2.21"

@OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)
kotlin {
  androidTarget()
  jvm()

  if (enableJsTargets) {
    js {
      browser()
      // https://youtrack.jetbrains.com/issue/KT-82989
      compilations.configureEach {
        compileTaskProvider.configure {
          incremental = false
          @Suppress("INVISIBLE_REFERENCE")
          incrementalJsKlib = false
        }
      }
    }
  }

  wasmJs { browser() }
  wasmWasi { nodejs() }

  applyDefaultHierarchyTemplate {
    common {
      group("concurrent") {
        withAndroidTarget()
        withJvm()
        withNative()
      }
      group("commonWasm") {
        withWasmJs()
        withWasmWasi()
      }
      group("commonJvm") {
        withAndroidTarget()
        withJvm()
      }
    }
  }

  configureOrCreateNativePlatforms()

  sourceSets {
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.coroutines)
      }
    }
  }

  targets.configureEach {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions.freeCompilerArgs.add(
          // Big yikes in how this was rolled out as noisy compiler warnings
          "-Xannotation-default-target=param-property"
        )
      }
    }
  }
}

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
}

// Sourced from https://kotlinlang.org/docs/native-target-support.html
fun KotlinMultiplatformExtension.configureOrCreateNativePlatforms() {
  // Tier 1
  linuxX64()
  macosArm64()
  iosArm64()
  iosSimulatorArm64()

  // Tier 2
  linuxArm64()
  watchosSimulatorArm64()
  watchosArm32()
  watchosArm64()
  tvosSimulatorArm64()
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

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
}
