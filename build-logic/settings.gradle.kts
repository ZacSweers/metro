// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
dependencyResolutionManagement {
  repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    google()
    gradlePluginPortal()
  }
  versionCatalogs { maybeCreate("libs").apply { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
