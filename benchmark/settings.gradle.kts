// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    mavenLocal() // For local testing
  }
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

rootProject.name = "metro-benchmark"

// Only include the parent build if METRO_VERSION is not set
// This allows testing against external Metro versions
val metroVersion = System.getenv("METRO_VERSION")

if (metroVersion.isNullOrEmpty()) {
  includeBuild("..")
}

val generatedProjects = file("generated-projects.txt")

if (generatedProjects.exists()) {
  for (p in generatedProjects.readLines()) {
    if (p.isBlank()) continue
    include(p)
  }
}
