// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "dev.zacsweers.metro.benchmark.startup.android.benchmark"
  compileSdk = 36

  defaultConfig {
    minSdk = 28
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Suppress emulator warning for local development
    testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
  }

  buildTypes {
    // This benchmark buildType is used for benchmarking, and should function like your
    // release build (for app, minified and optimized).
    create("benchmark") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
  }

  targetProjectPath = ":startup-android:app"
  // Load the target app in a separate process so that it can be restarted multiple times, which
  // is necessary for startup benchmarking to work correctly.
  // https://source.android.com/docs/core/tests/development/instr-self-e2e
  experimentalProperties["android.experimental.self-instrumenting"] = true

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(libs.androidx.benchmark.macro)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.uiautomator)
}

androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
