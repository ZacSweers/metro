// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.serialization)
  id("dev.zacsweers.metro")
}

kotlin {
  jvm()
  // TODO grow this out
  // macosArm64()
  // js { browser() }
  // @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }
  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.ktor.client)
        implementation(libs.ktor.client.contentNegotiation)
        implementation(libs.ktor.client.auth)
        implementation(libs.ktor.serialization.json)
        implementation(libs.okio)
        implementation(libs.picnic)
        implementation(libs.kotlinx.datetime)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
    jvmMain {
      dependencies {
        implementation(libs.ktor.client.engine.okhttp)
        implementation(libs.clikt)
        implementation(libs.okhttp)
        // To silence this stupid log https://www.slf4j.org/codes.html#StaticLoggerBinder
        implementation(libs.slf4jNop)
      }
    }
  }
  compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
}
