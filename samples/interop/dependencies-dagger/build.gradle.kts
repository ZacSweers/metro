// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  //  alias(libs.plugins.kotlin.kapt)
  alias(libs.plugins.ksp)
  id("dev.zacsweers.metro")
}

// TODO add KSP/KAPT dual mode?
dependencies {
  ksp(libs.dagger.compiler)
  implementation(libs.dagger.runtime)
  testImplementation(libs.kotlin.test)
}
