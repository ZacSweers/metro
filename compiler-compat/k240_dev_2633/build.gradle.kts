// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

dependencies {
  // The compiler version reported by these IDEs maps to 2.4.0-dev-2633, but the published
  // compiler artifact uses the IJ build version.
  compileOnly("org.jetbrains.kotlin:kotlin-compiler:2.4.0-ij261-32")
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
  implementation(project(":compiler-compat:k2320"))
}
