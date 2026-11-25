// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import java.net.URI

val metroMavenCentralMirror =
  Action<RepositoryHandler> {
    // Prefer the mirror to avoid Maven Central rate limits and outages.
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
  }

val metroMavenCentralRepositories =
  Action<RepositoryHandler> {
    metroMavenCentralMirror.execute(this)
    mavenCentral()
  }

val metroGoogleRepository =
  Action<RepositoryHandler> {
    google {
      content {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google.android")
        includeGroup("com.google.devtools.ksp")
        includeGroup("com.google.testing.platform")
      }
    }
  }

extra["metroMavenCentralRepositories"] = metroMavenCentralRepositories

extra["metroGoogleRepository"] = metroGoogleRepository

extra["metroSettingsRepositories"] =
  Action<Settings> {
    val options = extra.properties

    fun repositoryOption(name: String, default: Boolean = false): Boolean =
      options["metro.repositories.$name"] as Boolean? ?: default

    val buildLogic = repositoryOption("buildLogic")
    val includeMavenLocal = repositoryOption("mavenLocal")
    val includePluginMavenCentral = repositoryOption("pluginMavenCentral", default = true)
    val includeKotlinIntellijDependencies =
      repositoryOption("kotlinIntellijDependencies", default = true)
    val firstMavenRepository = options["metro.repositories.firstMavenRepository"] as URI?

    fun RepositoryHandler.addMetroRepositories(forPlugins: Boolean) {
      if (firstMavenRepository != null) {
        maven(firstMavenRepository)
      }

      if (!forPlugins || includePluginMavenCentral) {
        metroMavenCentralRepositories.execute(this)
      } else {
        metroMavenCentralMirror.execute(this)
      }

      if (!forPlugins || !buildLogic) {
        metroGoogleRepository.execute(this)
      }

      if (forPlugins || buildLogic) {
        gradlePluginPortal().content {
          includeModuleByRegex(".*", """.*\.gradle\.plugin""")
          // These plugin implementations are published to the portal.
          includeModule("com.gradle", "develocity-gradle-plugin")
          includeModule("me.champeau.jmh", "jmh-gradle-plugin")
          includeModule("me.tylerbwong.gradle.metalava", "plugin")
          includeModule("org.gradle", "test-retry-gradle-plugin")
          includeModule("org.gradle.kotlin", "gradle-kotlin-dsl-plugins")
          includeModule("org.jetbrains.intellij.platform", "intellij-platform-gradle-plugin")
          includeModule("org.jetbrains.kotlinx", "kotlinx-benchmark-plugin")
        }
      }

      if (includeMavenLocal) {
        mavenLocal()
      }

      if (buildLogic) {
        return
      }

      val kotlinRepositoryBase = "https://redirector.kotlinlang.org/maven"
      for (kotlinRepository in listOf("bootstrap", "dev/")) {
        maven("$kotlinRepositoryBase/$kotlinRepository") {
          content { includeGroupAndSubgroups("org.jetbrains.kotlin") }
        }
      }

      if (includeKotlinIntellijDependencies) {
        // Publications used by IJ
        // https://kotlinlang.slack.com/archives/C7L3JB43G/p1757001642402909
        maven("$kotlinRepositoryBase/intellij-dependencies/")
      }
    }

    pluginManagement.repositories { addMetroRepositories(forPlugins = true) }
    dependencyResolutionManagement.repositories { addMetroRepositories(forPlugins = false) }
  }
