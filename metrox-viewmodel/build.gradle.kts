// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_UMD
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("metro.publish")
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

  // Native targets supported by lifecycle-viewmodel
  // Tier 1
  iosArm64()
  iosSimulatorArm64()
  iosX64()
  linuxX64()
  macosArm64()
  macosX64()

  // Tier 2
  linuxArm64()
  tvosArm64()
  tvosSimulatorArm64()
  tvosX64()
  watchosArm32()
  watchosArm64()
  watchosSimulatorArm64()
  watchosX64()

  sourceSets {
    commonMain {
      dependencies {
        api(project(":runtime"))
        api(libs.jetbrains.lifecycle.viewmodel)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
  }

  targets.configureEach {
    val target = this
    compilations.configureEach {
      compileTaskProvider.configure {
        if (target.platformType == KotlinPlatformType.js) {
          compilerOptions.freeCompilerArgs.add(
            // These are all read at compile-time
            "-Xwarning-level=RUNTIME_ANNOTATION_NOT_SUPPORTED:disabled"
          )
        }
      }
    }
  }
}
