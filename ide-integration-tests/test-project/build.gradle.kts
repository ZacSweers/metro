// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  // TODO switch back to alias once on kotlin 2.3.20+
  //  alias(libs.plugins.kotlin.jvm)
  id("org.jetbrains.kotlin.jvm") version "2.3.20-Beta2"
  id("dev.zacsweers.metro")
}

kotlin { jvmToolchain(21) }

metro {
  generateAssistedFactories.set(true)
  enableTopLevelFunctionInjection.set(true)
}
