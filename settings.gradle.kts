// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
  }
  plugins { id("com.gradle.develocity") version "4.2.1" }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
  }
}

plugins { id("com.gradle.develocity") }

rootProject.name = "metro"

include(
  ":compiler",
  ":compiler-compat",
  ":compiler-compat:k230-dev9673",
  ":compiler-compat:k2220",
  ":compiler-tests",
  ":gradle-plugin",
  ":interop-dagger",
  ":runtime"
)

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
