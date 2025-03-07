// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
  id("dev.zacsweers.metro")
}

dependencies {
  ksp(libs.kotlinInject.compiler)
  implementation(libs.kotlinInject.runtime)
  testImplementation(libs.kotlin.test)
}
