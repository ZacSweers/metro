// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.compose)
  id("dev.zacsweers.metro")
}

kotlin {
  jvm()

  sourceSets {
    commonMain.dependencies {
      api("dev.zacsweers.metro:metrox-viewmodel-compose")
      implementation(compose.material3)
      implementation(compose.runtime)
    }
  }
}
