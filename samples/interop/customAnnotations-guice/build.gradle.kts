// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
}

metro { interop { includeGuice() } }

dependencies {
  implementation(libs.guice)
  implementation(libs.guice.assistedInject)
  testImplementation(libs.kotlin.test)
}
