// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

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
        // For PlatformUtils use
        implementation(libs.ktor.client)
      }
    }
  }

  targets
    .matching {
      it.platformType == KotlinPlatformType.js || it.platformType == KotlinPlatformType.wasm
    }
    .configureEach {
      compilations.configureEach {
        compileTaskProvider.configure {
          // These are all read at compile-time
          compilerOptions.freeCompilerArgs.add(
            "-Xsuppress-warning=RUNTIME_ANNOTATION_NOT_SUPPORTED"
          )
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
