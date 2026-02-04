// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
//  alias(libs.plugins.kotlin.jvm)
  id("org.jetbrains.kotlin.jvm") version "2.3.20-Beta2"
  id("dev.zacsweers.metro") version "1.0.0-LOCAL170"
}

kotlin { jvmToolchain(21) }

metro {
  generateAssistedFactories.set(true)
}
