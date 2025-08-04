// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.kotlin.plugin.serialization)
  alias(libs.plugins.compose)
  application
  id("dev.zacsweers.metro")
}

application { mainClass = "dev.zacsweers.metro.sample.multimodule.viewmodels.app.MainKt" }

dependencies {
  implementation(project(":multi-module-viewmodels:core"))
  implementation(project(":multi-module-viewmodels:screen-home"))
  implementation(project(":multi-module-viewmodels:screen-details"))

  implementation(libs.androidx.viewmodel)
  implementation(libs.jetbrains.navigation.desktop)
  implementation(compose.desktop.currentOs)

  // To set main dispatcher on desktop app
  implementation(libs.coroutines.swing)
}
