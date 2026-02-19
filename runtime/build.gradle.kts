// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_UMD
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("metro.publish")
}

/*
 * Here's the main hierarchy of variants. Any `expect` functions in one level of the tree are
 * `actual` functions in a (potentially indirect) child node.
 *
 * ```
 *   common
 *   |-- jvm
 *   |-- js
 *   '-- native
 *       |- unix
 *       |   |-- apple
 *       |   |   |-- iosArm64
 *       |   |   |-- iosX64
 *       |   |   |-- tvosArm64
 *       |   |   |-- watchosArm32
 *       |   |   |-- watchosArm64
 *       |   |   '-- watchosX86
 *       |   '-- linux
 *       |       '-- linuxX64
 *       '-- mingw
 *           '-- mingwX64
 * ```
 *
 * Every child of `unix` also includes a source set that depends on the pointer size:
 *
 *  * `sizet32` for watchOS, including watchOS 64-bit architectures
 *  * `sizet64` for everything else
 */
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
      group("nonConcurrent") {
        withJs()
        withWasmJs()
        withWasmWasi()
      }
      group("concurrentTest") {
        withJvm()
        withNative()
      }
    }
  }

  sourceSets {
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.coroutines)
        implementation(libs.coroutines.test)
      }
    }
    val concurrentTest by creating { dependsOn(commonTest.get()) }
    jvmTest { dependsOn(concurrentTest) }
    nativeTest { dependsOn(concurrentTest) }
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
