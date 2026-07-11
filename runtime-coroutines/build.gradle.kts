// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("metro.base")
  id("metro.publish")
}

metroProject { configureCommonKmpTargets("metro-runtime-coroutines") }

kotlin {
  sourceSets {
    commonMain {
      dependencies {
        api(project(":runtime"))
        // NOTE: kotlinx-coroutines is deliberately NOT a common dependency. The web (JS/Wasm)
        // SuspendDoubleCheck is lock-free and stdlib-only, keeping the web klibs free of any
        // kotlinx.coroutines dependency.
      }
    }
    getByName("nonWebMain") {
      dependencies {
        // Mutex-based SuspendDoubleCheck synchronization on JVM/Native
        api(libs.coroutines)
      }
    }
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.coroutines.test)
      }
    }
  }

  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
}

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
}
