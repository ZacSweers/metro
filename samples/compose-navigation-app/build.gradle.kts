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
  namespace = "dev.zacsweers.metro.sample.android"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.zacsweers.metro.sample.android"
    minSdk = 28
    targetSdk = 35
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
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.viewmodel)
  implementation("androidx.navigation:navigation-compose:2.8.9")
  implementation("androidx.compose.material:material-navigation:1.7.8")
  implementation("androidx.compose.material3:material3:1.3.2")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
