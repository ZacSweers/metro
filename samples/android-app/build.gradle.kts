// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  id("dev.zacsweers.metro")
}

android {
  namespace = "dev.zacsweers.metro.sample.android"

  defaultConfig {
    applicationId = "dev.zacsweers.metro.sample.android"
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes { release { isMinifyEnabled = false } }

  compileOptions {
    val javaVersion = libs.versions.jvmTarget.get().let(JavaVersion::toVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
  }

  testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
  implementation("dev.zacsweers.metro:metrox-android")
  implementation("dev.zacsweers.metro:metrox-viewmodel")
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.viewmodelKtx)
  implementation(libs.androidx.work)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test)
}
