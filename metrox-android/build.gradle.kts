// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.android.library)
  id("metro.publish")
}

metroArtifact {
  artifactId.set("metrox-android")
  name.set("Metrox Android")
  packaging.set("aar")
}

android {
  namespace = "dev.zacsweers.metrox.android"

  defaultConfig { minSdk = 28 }
}

dependencies {
  api(project(":runtime"))

  implementation(libs.androidx.activity)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.core)
}
