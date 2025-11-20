// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  id("dev.zacsweers.metro")
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.kotlin.plugin.serialization)
}

android {
  namespace = "dev.zacsweers.metro.sample.androidviewmodel"

  defaultConfig {
    applicationId = "dev.zacsweers.metro.sample.androidviewmodel"
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes { release { isMinifyEnabled = false } }

  compileOptions {
    val javaVersion = libs.versions.jvmTarget.get().let(JavaVersion::toVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
  }
}

dependencies {
  implementation("dev.zacsweers.metro:metrox-android")
  implementation(libs.androidx.activity)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.materialNavigation)
  implementation(libs.androidx.core)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.navigationCompose)
  implementation(libs.androidx.viewmodel)
  implementation(libs.kotlinx.serialization.json)
}
