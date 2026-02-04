// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    // Metro SNAPSHOT from local publish — run `./gradlew publishToMavenLocal` from the root first
    mavenLocal()
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
  }
}

dependencyResolutionManagement {
  repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
  }
}

rootProject.name = "metro-ide-test-project"
