// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "dev.zacsweers.metro.benchmark.startup.android.microbenchmark"
  compileSdk = 36

  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"

    // Suppress emulator warning for local development
    testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
  }

  buildTypes {
    create("benchmark") {
      isDebuggable = false
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
  }

  targetProjectPath = ":startup-android:app"
  experimentalProperties["android.experimental.self-instrumenting"] = true

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(libs.androidx.benchmark.micro)
  implementation(libs.androidx.test.runner)
  implementation(libs.androidx.test.ext.junit)

  // Depend on the generated app component
  implementation(project(":app:component"))
}

androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
