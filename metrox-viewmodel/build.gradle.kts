// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_UMD
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.compose)
  alias(libs.plugins.mavenPublish)
}

kotlin {
  jvm()
  js(IR) {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions {
          moduleKind.set(MODULE_UMD)
          sourceMap.set(true)
        }
      }
    }
    nodejs { testTask { useMocha { timeout = "30s" } } }
    browser()
    binaries.executable()
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    binaries.executable()
    browser {}
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmWasi {
    binaries.executable()
    nodejs()
  }

  configureOrCreateNativePlatforms()

  @Suppress("OPT_IN_USAGE")
  applyDefaultHierarchyTemplate {
    common {
      group("concurrentTest") {
        withJvm()
        withNative()
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":runtime"))
      api(libs.androidx.viewmodelCompose)
      api(compose.runtime)
    }
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
