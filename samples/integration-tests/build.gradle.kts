// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.library)
  id("dev.zacsweers.metro")
}

android {
  namespace = "dev.zacsweers.metro.test.integration.android"

  buildFeatures { viewBinding = true }

  compileOptions {
    val javaVersion = libs.versions.jvmTarget.get().let(JavaVersion::toVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
  }
}

@OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)
kotlin {
  androidTarget()
  jvm()

  js { browser() }
  wasmJs { browser() }
  wasmWasi { nodejs() }

  applyDefaultHierarchyTemplate {
    group("commonWasm") {
      withWasmJs()
      withWasmWasi()
    }
    group("commonJvm") {
      withAndroidTarget()
      withJvm()
    }
  }

  configureOrCreateNativePlatforms()

  sourceSets {
    commonTest { dependencies { implementation(libs.kotlin.test) } }
    maybeCreate("commonJvmMain").apply { dependsOn(commonMain.get()) }
    maybeCreate("commonWasmMain").apply { dependsOn(commonMain.get()) }
    maybeCreate("commonJvmTest").apply { dependsOn(commonTest.get()) }
    maybeCreate("commonWasmTest").apply { dependsOn(commonTest.get()) }
  }

  targets.configureEach {
    val target = this
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions.freeCompilerArgs.add(
          // Big yikes in how this was rolled out as noisy compiler warnings
          "-Xannotation-default-target=param-property"
        )
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
