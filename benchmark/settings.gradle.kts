// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    mavenLocal() // For local testing
  }
  plugins { id("com.gradle.develocity") version "4.2.2" }
}

dependencyResolutionManagement {
  versionCatalogs {
    maybeCreate("libs").apply {
      from(files("../gradle/libs.versions.toml"))
      // Override Metro version if METRO_VERSION is set
      val metroVersion = System.getenv("METRO_VERSION")
      if (!metroVersion.isNullOrEmpty()) {
        version("metro", metroVersion)
      }
    }
  }
  repositories {
    mavenCentral()
    google()
    mavenLocal() // For local testing
  }
}

plugins { id("com.gradle.develocity") }

rootProject.name = "metro-benchmark"

// Only include the parent build if METRO_VERSION is not set
// This allows testing against external Metro versions
val metroVersion = System.getenv("METRO_VERSION")

if (metroVersion.isNullOrEmpty()) {
  includeBuild("..")
}

val generatedProjects = file("generated-projects.txt")

if (generatedProjects.exists()) {
  // Static startup benchmark modules
  include(":startup-jvm")
  include(":startup-jvm:minified-jar")
  include(":startup-jvm-minified")
  include(":startup-android:app")
  include(":startup-android:benchmark")
  include(":startup-android:microbenchmark")

  for (p in generatedProjects.readLines()) {
    if (p.isBlank()) continue
    include(p)
  }
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
