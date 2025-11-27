// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.compose)
  alias(libs.plugins.kotlin.plugin.serialization)
  alias(libs.plugins.compose)
  id("dev.zacsweers.metro")
}

kotlin {
  androidTarget()
  jvm {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    mainRun { mainClass = "dev.zacsweers.metro.sample.composeviewmodels.app.MainKt" }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":compose-viewmodels:core"))
      implementation(project(":compose-viewmodels:screen-home"))
      implementation(project(":compose-viewmodels:screen-details"))

      implementation(libs.jetbrains.navigation.desktop)
      implementation(libs.kotlinx.serialization.json)
      implementation(compose.material3)
      implementation(compose.runtime)
    }
    androidMain {
      dependencies {
        implementation("dev.zacsweers.metro:metrox-android")
        implementation(libs.androidx.activity)
        implementation(libs.androidx.appcompat)
        implementation(libs.androidx.core)
        implementation(libs.androidx.lifecycle.runtime.compose)
      }
    }
    jvmMain {
      dependencies {
        implementation(compose.desktop.currentOs)
        // To set main dispatcher on desktop app
        implementation(libs.coroutines.swing)
      }
    }
  }
}

android {
  namespace = "dev.zacsweers.metro.sample.composeviewmodels"

  defaultConfig {
    applicationId = "dev.zacsweers.metro.sample.composeviewmodels"
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes { release { isMinifyEnabled = false } }

  compileOptions {
    val javaVersion = libs.versions.jvmTarget.get().let(JavaVersion::toVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
  }
}
