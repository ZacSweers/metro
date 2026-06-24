// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.android.test) }

val runtimeTracingEnabled =
  providers.gradleProperty("metro.benchmark.runtimeTracing").map(String::toBoolean).getOrElse(false)

android {
  namespace = "dev.zacsweers.metro.benchmark.startup.android.microbenchmark"
  compileSdk = 36

  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    buildConfigField("boolean", "METRO_RUNTIME_TRACING", runtimeTracingEnabled.toString())

    // Suppress emulator warning for quick testing but not actual benchmark measurements
    testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "SIMPLEPERF,EMULATOR"
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

  buildFeatures { buildConfig = true }
}

dependencies {
  implementation(libs.androidx.benchmark.micro)
  implementation(libs.androidx.test.runner)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.tracing.wire)

  // Depend on the generated app component
  implementation(project(":app:component"))
}

androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
