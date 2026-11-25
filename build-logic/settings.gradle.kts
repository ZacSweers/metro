// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  extra["metro.repositories.buildLogic"] = true
  apply(from = "../gradle/repositories.gradle.kts")
  val metroSettingsRepositories: Action<Settings> by extra
  metroSettingsRepositories.execute(settings)
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
