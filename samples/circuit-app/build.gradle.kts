// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.plugin.compose)
  id("dev.zacsweers.metro")
//  alias(libs.plugins.ksp)
}

metro {
  enableCircuitCodegen.set(true)
}

//ksp { arg("circuit.codegen.mode", "metro") }

// TODO broken for now until
//  https://youtrack.jetbrains.com/issue/KT-76715
//  https://youtrack.jetbrains.com/issue/KT-66735
// metro { enableTopLevelFunctionInjection.set(true) }

kotlin {
  jvm {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    mainRun { mainClass.set("dev.zacsweers.metro.sample.circuit.MainKt") }
  }
  // Second target for KSP's commonMain gen to work
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
      kotlin {
        // needed so that common sources are picked up
        srcDir("build/generated/ksp/metadata/commonMain/kotlin")
      }
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
    wasmJsMain { dependencies { implementation(compose.components.resources) } }
  }
}

//dependencies { add("kspCommonMainMetadata", libs.circuit.codegen) }

//tasks.withType<KotlinCompilationTask<*>>().configureEach {
//  if (this is AbstractKotlinCompile<*>) {
//    // Disable incremental in this project because we're generating top-level declarations
//    // TODO remove after Soon™️ (2.2?)
//    incremental = false
//  }
//
//  dependsOn("kspCommonMainKotlinMetadata")
//}
