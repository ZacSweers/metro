// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
buildscript {
  configurations.configureEach {
    // Gradle's embedded Kotlin pins org.jetbrains:annotations to strictly 13.0,
    // but AGP and other classpath dependencies require 23.0.0.
    resolutionStrategy.force("org.jetbrains:annotations:26.0.2-1")
  }
}

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.kapt) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.plugin.compose) apply false
  alias(libs.plugins.ksp) apply false
  id("dev.zacsweers.metro") apply false
  alias(libs.plugins.mavenPublish) apply false // wat
  alias(libs.plugins.compose) apply false
  alias(libs.plugins.kotlin.plugin.serialization) apply false
  id("metro.yarnNode")
}

allprojects { apply(plugin = "metro.spotless") }

subprojects { apply(plugin = "metro.base") }
