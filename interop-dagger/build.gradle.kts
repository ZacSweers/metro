// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.testkit)
}

dependencies {
  api(project(":runtime"))
  api(project(":interop-javax"))
  api(project(":interop-jakarta"))
  api(libs.dagger.runtime)
}
