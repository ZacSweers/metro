// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  val metroVersion =
    file("../../gradle.properties")
      .readLines()
      .first { it.startsWith("VERSION_NAME=") }
      .substringAfter("=")
  repositories {
    maven(uri("../../build/functionalTestRepo"))
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
  }
  plugins { id("dev.zacsweers.metro") version metroVersion }
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../../gradle/libs.versions.toml")) } }
  repositories {
    maven(uri("../../build/functionalTestRepo"))
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    google()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
  }
}

rootProject.name = "metro-ide-test-project"

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
