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
  }
}

rootProject.name = "metro-benchmark"

includeBuild("..")

val generatedProjects = file("generated-projects.txt")

if (generatedProjects.exists()) {
  for (p in generatedProjects.readLines()) {
    if (p.isBlank()) continue
    include(p)
  }
}
