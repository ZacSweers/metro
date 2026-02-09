// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.testkit)
  id("metro.publish")
}

dependencies {
  api(project(":runtime"))
  api(libs.jakartaInject)
}
