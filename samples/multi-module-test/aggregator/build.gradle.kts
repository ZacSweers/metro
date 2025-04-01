// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
}

dependencies {
  implementation(project(":multi-module-test:common"))
  implementation(project(":multi-module-test:contributor"))
  implementation(project(":multi-module-test:parent-graph"))
}
