// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.android.application) }

android {
  namespace = "dev.zacsweers.metro.sample.composeviewmodels.androidapp"

  defaultConfig {
    applicationId = "dev.zacsweers.metro.sample.composeviewmodels"
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes { release { isMinifyEnabled = false } }
}

dependencies { implementation(project(":compose-viewmodels:app")) }
