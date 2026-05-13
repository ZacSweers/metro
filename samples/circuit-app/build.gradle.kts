// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.plugin.compose)
  id("dev.zacsweers.metro")
}

@OptIn(ExperimentalMetroGradleApi::class) metro { enableCircuitCodegen.set(true) }

kotlin {
  jvm {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    mainRun { mainClass.set("dev.zacsweers.metro.sample.circuit.MainKt") }
  }
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName.set("counterApp")
    browser { commonWebpackConfig { outputFileName = "counterApp.js" } }
    binaries.executable()
  }
  // TODO others?
  //  macosArm64()
  sourceSets {
    commonMain {
      dependencies {
        // Circuit dependencies
        implementation(libs.circuit.foundation)
        implementation(libs.circuit.runtime)
        implementation(libs.circuit.codegenAnnotations)

        // Compose dependencies
        implementation(libs.compose.runtime)
        implementation(libs.compose.material3)
        implementation(libs.compose.materialIcons)
        implementation(libs.compose.foundation)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
    jvmMain { dependencies { implementation(compose.desktop.currentOs) } }
    wasmJsMain { dependencies { implementation(libs.compose.components.resources) } }
  }
}
