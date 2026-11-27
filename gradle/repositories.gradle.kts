// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

import java.net.URI

val metroMavenCentralMirrorUrl = "https://maven-central.storage-download.googleapis.com/maven2/"
val metroGradlePluginPortalMirrorUrl =
  "https://cache-redirector.jetbrains.com/plugins.gradle.org/m2"

val metroMavenCentralMirror =
  Action<RepositoryHandler> {
    // Prefer the mirror to avoid Maven Central rate limits and outages.
    maven(metroMavenCentralMirrorUrl)
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

extra["metroMavenCentralMirrorUrl"] = metroMavenCentralMirrorUrl

extra["metroGradlePluginPortalMirrorUrl"] = metroGradlePluginPortalMirrorUrl

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

    val pluginPortalContent =
      Action<RepositoryContentDescriptor> {
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

    fun RepositoryHandler.addMetroRepositories(forPlugins: Boolean) {
      val overrideRepository = firstMavenRepository?.let { maven(it) }
      metroMavenCentralMirror.execute(this)

      if (!forPlugins || !buildLogic) {
        metroGoogleRepository.execute(this)
      }

      val usePluginPortal = forPlugins || buildLogic
      if (usePluginPortal) {
        maven(metroGradlePluginPortalMirrorUrl).content(pluginPortalContent)
      }

      val localRepository = if (includeMavenLocal) mavenLocal() else null
      if (!buildLogic) {
        val kotlinRepositoryBase = "https://redirector.kotlinlang.org/maven"
        val kotlinBootstrap =
          maven("$kotlinRepositoryBase/bootstrap") {
            content { includeGroupAndSubgroups("org.jetbrains.kotlin") }
          }
        maven("$kotlinRepositoryBase/dev/") {
          content { includeGroupAndSubgroups("org.jetbrains.kotlin") }
        }

        if (includeKotlinIntellijDependencies) {
          // Publications used by IJ
          // https://kotlinlang.slack.com/archives/C7L3JB43G/p1757001642402909
          maven("$kotlinRepositoryBase/intellij-dependencies/")
        }

        if (!forPlugins) {
          // Central rate limits too often. Only these repositories serve Kotlin dev versions.
          val devRepositories: Set<ArtifactRepository> =
            setOfNotNull(overrideRepository, localRepository, kotlinBootstrap)

          // A repository named in exclusiveContent only serves content that matches the filter.
          // The override and local repositories also hold other artifacts, such as Metro's own.
          configureEach {
            if (this !in devRepositories) {
              content {
                excludeVersionByRegex(
                  """org\.jetbrains\.kotlin(\..*)?""",
                  ".*",
                  """.+-dev(?:-.+)?""",
                )
              }
            }
          }
        }
      }

      if (!forPlugins || includePluginMavenCentral) {
        mavenCentral()
      }
      if (usePluginPortal) {
        gradlePluginPortal().content(pluginPortalContent)
      }
    }

    pluginManagement.repositories { addMetroRepositories(forPlugins = true) }
    dependencyResolutionManagement.repositories { addMetroRepositories(forPlugins = false) }
  }
