// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.mavenPublish)
}

android {
  namespace = "dev.zacsweers.metrox.android"

  compileSdk = 36

  compileOptions {
    val javaVersion = libs.versions.jvmTarget.get().let(JavaVersion::toVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
  }
}

dependencies {
  api(project(":runtime"))

  implementation(libs.androidx.activity)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.core)
}
