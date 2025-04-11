// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }
  plugins { id("com.gradle.develocity") version "4.0" }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
  }
}

plugins { id("com.gradle.develocity") }

rootProject.name = "metro"

include(":compiler", ":compiler-tests", ":integration-tests", ":interop-dagger", ":runtime")

includeBuild("gradle-plugin") {
  dependencySubstitution {
    substitute(module("dev.zacsweers.metro:gradle-plugin")).using(project(":"))
  }
}

val VERSION_NAME: String by extra.properties

develocity {
  buildScan {
    termsOfUseUrl = "https://gradle.com/terms-of-service"
    termsOfUseAgree = "yes"

    tag(if (System.getenv("CI").isNullOrBlank()) "Local" else "CI")
    tag(VERSION_NAME)

    obfuscation {
      username { "Redacted" }
      hostname { "Redacted" }
      ipAddresses { addresses -> addresses.map { "0.0.0.0" } }
    }
  }
}
