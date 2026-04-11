// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("metro.base")
  id("metro.publish")
}

metroProject { configureCommonKmpTargets("metrox-viewmodel", requiresAndroidXDeps = true) }

kotlin {
  sourceSets {
    commonMain {
      dependencies {
        api(project(":runtime"))
        api(libs.kotlin.stdlib.published)
        api(libs.jetbrains.lifecycle.viewmodel)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
    webMain {
      dependencies {
        // https://youtrack.jetbrains.com/issue/KT-84582
        api(libs.kotlin.stdlib)
      }
    }
  }
}
