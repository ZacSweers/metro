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

include(":compiler", ":integration-tests", ":runtime")

includeBuild("gradle-plugin") {
  dependencySubstitution {
    substitute(module("dev.zacsweers.metro:gradle-plugin")).using(project(":"))
  }
}
