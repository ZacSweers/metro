// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.plugin.compose)
  id("dev.zacsweers.metro")
  alias(libs.plugins.ksp)
}

ksp { arg("circuit.codegen.mode", "metro") }

metro { enableTopLevelFunctionInjection.set(true) }

kotlin {
  jvm {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    mainRun { mainClass.set("dev.zacsweers.metro.sample.circuit.MainKt") }
  }
  // macosArm64()
  // js { browser() }
  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }
  sourceSets {
    commonMain {
      kotlin {
        // needed so that common sources are picked up
        srcDir("build/generated/ksp/metadata/commonMain/kotlin")
      }
      dependencies {
        // Circuit dependencies
        implementation("com.slack.circuit:circuit-foundation:0.27.0")
        implementation("com.slack.circuit:circuit-runtime:0.27.0")
        implementation("com.slack.circuit:circuit-codegen-annotations:0.27.0")

        // Compose dependencies
        implementation(libs.compose.runtime)
        implementation(libs.compose.material3)
        implementation(libs.compose.materialIcons)
        implementation(libs.compose.foundation)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
    jvmMain { dependencies { implementation(compose.desktop.currentOs) } }
  }
}

dependencies { add("kspCommonMainMetadata", libs.circuit.codegen) }

tasks.withType<KotlinCompilationTask<*>>().configureEach {
  if (this is KotlinCompile) {
    // Disable incremental in this project because we're generating top-level declarations
    // TODO remove after Soon™️ (2.2?)
    incremental = false
  }

  dependsOn("kspCommonMainKotlinMetadata")
}
