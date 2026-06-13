// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
}

metro { interop { includeHilt() } }

dependencies {
  implementation(libs.hilt.core)
  implementation(project(":interop:customAnnotations-hilt:lib"))
  testImplementation(libs.kotlin.test)
}
