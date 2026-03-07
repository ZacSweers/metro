// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  includeBuild("../build-logic")
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
    maven("https://redirector.kotlinlang.org/maven/intellij-dependencies/")
  }
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../gradle/libs.versions.toml")) } }
  repositories {
    mavenCentral()
    google()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
    maven("https://redirector.kotlinlang.org/maven/intellij-dependencies/")
    // IDE Starter artifacts
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://www.jetbrains.com/intellij-repository/snapshots")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
  }
}

plugins { id("com.gradle.develocity") version "4.3.2" }

rootProject.name = "metro-ide-integration-tests"

// Skip the Metro included build when artifacts are pre-built (e.g., CI matrix jobs).
// This avoids configuring the full Metro project (and downloading Konan, etc.).
if (System.getenv("METRO_PREBUILT") == null) {
  includeBuild("..") { name = "metro" }
}

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
