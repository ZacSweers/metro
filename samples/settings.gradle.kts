// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
  plugins {
    id("com.gradle.develocity") version "4.2.2"
    id("com.android.settings") version "8.13.1"
  }
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../gradle/libs.versions.toml")) } }
  repositories {
    mavenCentral()
    google()
  }
}

plugins {
  id("com.gradle.develocity")
  id("com.android.settings")
}

android {
  compileSdk = 36
  targetSdk = 36
  minSdk = 28
}

rootProject.name = "metro-samples"

include(
  ":android-app",
  ":circuit-app",
  ":compose-viewmodels:app",
  ":compose-viewmodels:core",
  ":compose-viewmodels:screen-details",
  ":compose-viewmodels:screen-home",
  ":integration-tests",
  ":interop:customAnnotations-dagger",
  ":interop:customAnnotations-guice",
  ":interop:customAnnotations-kotlinInject",
  ":interop:dependencies-dagger",
  ":interop:dependencies-kotlinInject",
  ":weather-app",
)

includeBuild("..")

develocity {
  buildScan {
    termsOfUseUrl = "https://gradle.com/terms-of-service"
    termsOfUseAgree = "yes"

    tag(if (System.getenv("CI").isNullOrBlank()) "Local" else "CI")

    obfuscation {
      username { "Redacted" }
      hostname { "Redacted" }
      ipAddresses { addresses -> addresses.map { "0.0.0.0" } }
    }
  }
}
