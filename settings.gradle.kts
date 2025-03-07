// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "metro"

include(":compiler", ":compiler-tests", ":integration-tests", ":interop-dagger", ":runtime")

includeBuild("gradle-plugin") {
  dependencySubstitution {
    substitute(module("dev.zacsweers.metro:gradle-plugin")).using(project(":"))
  }
}
