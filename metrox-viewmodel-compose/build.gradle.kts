// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_UMD

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.compose)
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

  // Compose-supported native targets
  iosArm64()
  iosSimulatorArm64()
  iosX64()
  macosArm64()
  macosX64()

  sourceSets {
    commonMain {
      dependencies {
        api(project(":metrox-viewmodel"))
        api(libs.jetbrains.lifecycle.viewmodel.compose)
      }
    }
    commonTest { dependencies { api(libs.compose.ui.test) } }
    jvmTest {
      dependencies {
        implementation(libs.junit)
        implementation(libs.truth)
        implementation(compose.desktop.currentOs)
        implementation(libs.compose.ui.test.junit4)
      }
    }
  }
}
