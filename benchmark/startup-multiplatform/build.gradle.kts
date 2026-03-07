// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlinx.benchmark)
  alias(libs.plugins.kotlin.allopen)
}

// Required for JMH on JVM - make benchmark classes open
allOpen { annotation("org.openjdk.jmh.annotations.State") }

// Configurable native targets
val enableMacos = providers.gradleProperty("benchmark.native.macos").orNull.toBoolean()
val enableLinux = providers.gradleProperty("benchmark.native.linux").orNull.toBoolean()
val enableWindows = providers.gradleProperty("benchmark.native.windows").orNull.toBoolean()

kotlin {
  jvm()

  js(IR) { nodejs() }

  @OptIn(ExperimentalWasmDsl::class) wasmJs { nodejs() }

  if (enableMacos) {
    macosArm64()
    macosX64()
  } else if (enableLinux) {
    linuxX64()
  } else if (enableWindows) {
    mingwX64()
  }

  sourceSets {
    commonMain {
      dependencies {
        implementation(project(":app:component"))
        implementation(libs.kotlinx.benchmark.runtime)
      }
    }
  }
}

benchmark {
  targets {
    register("jvm")
    register("js")
    register("wasmJs")
    register("macosArm64")
    register("macosX64")
    if (enableLinux) register("linuxX64")
    if (enableWindows) register("mingwX64")
  }

  configurations {
    named("main") {
      warmups = 4
      iterations = 10
      iterationTime = 1
      iterationTimeUnit = "s"
      outputTimeUnit = "ms"
      reportFormat = "json"
    }
    // Very quick smoke-test variant
    register("smoke") {
      include(".*Startup.*")
      warmups = 2
      iterations = 3
      iterationTime = 500
      iterationTimeUnit = "ms"
      reportFormat = "json"
    }
  }
}
