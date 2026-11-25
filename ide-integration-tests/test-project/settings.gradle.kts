// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  val metroVersion =
    file("../../gradle.properties")
      .readLines()
      .first { it.startsWith("VERSION_NAME=") }
      .substringAfter("=")
  extra["metro.repositories.firstMavenRepository"] = uri("../../build/functionalTestRepo")
  extra["metro.repositories.kotlinIntellijDependencies"] = false
  apply(from = "../../gradle/repositories.gradle.kts")
  val metroSettingsRepositories: Action<Settings> by extra
  metroSettingsRepositories.execute(settings)
  plugins { id("dev.zacsweers.metro") version metroVersion }
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "metro-ide-test-project"

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
