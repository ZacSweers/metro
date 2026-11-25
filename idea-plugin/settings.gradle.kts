// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
  includeBuild("../build-logic")
  apply(from = "../gradle/repositories.gradle.kts")
  val metroSettingsRepositories: Action<Settings> by extra
  metroSettingsRepositories.execute(settings)
  plugins { id("com.gradle.develocity") version "4.6.1" }
}

dependencyResolutionManagement {
  versionCatalogs { maybeCreate("libs").apply { from(files("../gradle/libs.versions.toml")) } }
}

plugins { id("com.gradle.develocity") }

rootProject.name = "metro-idea-plugin"

includeBuild("..")

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

enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
