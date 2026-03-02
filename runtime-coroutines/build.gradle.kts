// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_UMD
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("metro.publish")
}

kotlin {
  jvm()
  js(IR) {
    outputModuleName = "metro-runtime-coroutines-js"
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
    outputModuleName = "metro-runtime-coroutines-wasmjs"
    binaries.executable()
    browser {}
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmWasi {
    binaries.executable()
    nodejs()
  }

  configureOrCreateNativePlatforms()

  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    common {
      group("wasm") {
        withWasmJs()
        withWasmWasi()
      }
      group("web") {
        withJs()
        withWasmJs()
        withWasmWasi()
      }
      group("nonWeb") {
        withJvm()
        withNative()
      }
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        api(project(":runtime"))
        api(libs.coroutines)
      }
    }
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.coroutines.test)
      }
    }
  }

  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

  targets
    .matching {
      it.platformType == KotlinPlatformType.js || it.platformType == KotlinPlatformType.wasm
    }
    .configureEach {
      compilations.configureEach {
        compileTaskProvider.configure {
          compilerOptions {
            freeCompilerArgs.add("-Xklib-duplicated-unique-name-strategy=allow-all-with-warning")
          }
        }
      }
    }
}

// Sourced from https://kotlinlang.org/docs/native-target-support.html
fun KotlinMultiplatformExtension.configureOrCreateNativePlatforms() {
  // Tier 1
  iosArm64()
  iosSimulatorArm64()
  macosArm64()

  // Tier 2
  linuxArm64()
  linuxX64()
  tvosArm64()
  tvosSimulatorArm64()
  watchosArm32()
  watchosArm64()
  watchosSimulatorArm64()

  // Tier 3
  androidNativeArm32()
  androidNativeArm64()
  androidNativeX64()
  androidNativeX86()
  iosX64()
  macosX64()
  mingwX64()
  tvosX64()
  watchosDeviceArm64()
  watchosX64()
}

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
}
