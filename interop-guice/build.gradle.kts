// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.testkit)
}

dependencies {
  api(project(":runtime"))
  // Guice dropped javax.inject in 7.0
  api(project(":interop-jakarta"))
  api(libs.guice)
}
