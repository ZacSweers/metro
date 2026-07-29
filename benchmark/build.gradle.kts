// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.android.kmp) apply false
  alias(libs.plugins.kotlin.allopen) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kotlin.kapt) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.test) apply false
  alias(libs.plugins.jmh) apply false
  alias(libs.plugins.kotlinx.benchmark) apply false
  alias(libs.plugins.benchmark) apply false
  alias(libs.plugins.koin.compiler) apply false
  alias(libs.plugins.metro) apply false
  alias(libs.plugins.anvil) apply false
  alias(libs.plugins.mavenPublish) apply false // wat
  id("metro.base") apply false
  id("base")
}

subprojects { apply(plugin = "metro.base") }

tasks.register("benchmarkCooldown") {
  group = "benchmark"
  description = "Pauses between Gradle Profiler iterations to keep the benchmark host stable."

  doLast {
    val seconds =
      providers.environmentVariable("BENCHMARK_COOLDOWN_SECONDS").getOrElse("30").toLong()
    require(seconds >= 0) { "BENCHMARK_COOLDOWN_SECONDS must be non-negative" }
    Thread.sleep(seconds * 1_000)
  }
}
