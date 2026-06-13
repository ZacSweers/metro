// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.compose)
  id("metro.base")
  id("metro.publish")
}

metroProject { configureCommonKmpTargets("metrox-viewmodel-compose", isComposeTarget = true) }

kotlin {
  sourceSets {
    commonMain {
      dependencies {
        api(project(":metrox-viewmodel"))
        api(libs.jetbrains.lifecycle.viewmodel.compose)
      }
    }
    commonTest { dependencies { api(libs.compose.ui.test) } }
    jvmTest {
      dependencies {
        implementation(libs.junit)
        implementation(libs.truth)
        implementation(compose.desktop.currentOs)
        implementation(libs.compose.ui.test.junit4)
      }
    }
  }
}
