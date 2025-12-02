// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import dev.zacsweers.metro.gradle.DelicateMetroGradleApi

plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
}

@OptIn(DelicateMetroGradleApi::class)
metro { reportsDestination.set(layout.buildDirectory.dir("reports/metro")) }

kotlin { compilerOptions { optIn.add("kotlin.time.ExperimentalTime") } }
