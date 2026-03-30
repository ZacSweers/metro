// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("metro.base")
  id("metro.publish")
}

metroProject { configureCommonKmpTargets("metro-runtime") }

kotlin {
  sourceSets {
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.coroutines)
        implementation(libs.coroutines.test)
      }
    }
  }

  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
}
