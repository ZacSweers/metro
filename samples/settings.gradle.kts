// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../gradle/libs.versions.toml")) } }
  repositories {
    mavenCentral()
    google()
    // For ktfmt
    maven("https://jitpack.io")
  }
}

plugins { id("com.android.settings") version "8.9.3" }

android {
  compileSdk = 36
  targetSdk = 36
  minSdk = 28
}

rootProject.name = "metro-samples"

include(
  ":android-app",
  ":compose-navigation-app",
  ":circuit-app",
  ":integration-tests",
  ":interop:customAnnotations-dagger",
  ":interop:customAnnotations-kotlinInject",
  ":interop:dependencies-dagger",
  ":interop:dependencies-kotlinInject",
  ":weather-app",
  ":multi-module-test:common",
  ":multi-module-test:parent-graph",
  ":multi-module-test:child-graph",
  ":multi-module-test:contributor",
  ":multi-module-test:aggregator",
  ":multi-module-test:app",
)

includeBuild("..")
