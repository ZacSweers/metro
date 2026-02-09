// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
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
  alias(libs.plugins.metro) apply false
  alias(libs.plugins.anvil) apply false
  alias(libs.plugins.mavenPublish) apply false // wat
  id("metro.spotless")
}

allprojects { apply(plugin = "metro.spotless") }

subprojects { apply(plugin = "metro.base") }
