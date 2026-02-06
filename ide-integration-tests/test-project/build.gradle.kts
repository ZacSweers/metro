// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
}

kotlin { jvmToolchain(21) }

metro {
  generateAssistedFactories.set(true)
}
