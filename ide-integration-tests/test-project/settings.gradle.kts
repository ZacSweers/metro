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
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
  }
  plugins {
    id("dev.zacsweers.metro") version metroVersion
  }
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../../gradle/libs.versions.toml")) } }
  repositories {
    maven(uri("../../build/functionalTestRepo"))
    mavenCentral()
    google()
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev/")
  }
}

rootProject.name = "metro-ide-test-project"
