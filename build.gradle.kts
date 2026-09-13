// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import kotlinx.validation.ExperimentalBCVApi
import org.gradle.process.ProcessExecutionException

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.lint) apply false
  alias(libs.plugins.android.kmp) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.mavenPublish) apply false
  alias(libs.plugins.binaryCompatibilityValidator)
  alias(libs.plugins.poko) apply false
  alias(libs.plugins.wire) apply false
  alias(libs.plugins.testkit) apply false
  id("metro.yarnNode")
}

// Autoconfigure git to use project-specific config (hooks)
if (file(".git").exists()) {
  val gitAvailable =
    try {
      providers.exec { commandLine("git", "--version") }.result.get()
      true
    } catch (_: ProcessExecutionException) {
      false
    }
  if (gitAvailable) {
    val expectedIncludePath = "../config/git/.gitconfig"
    val includePath =
      providers
        .exec {
          commandLine("git", "config", "--local", "--default", "", "--get", "include.path")
        }
        .standardOutput
        .asText
        .map { it.trim() }
        .getOrElse("")
    if (includePath != expectedIncludePath) {
      providers
        .exec { commandLine("git", "config", "--local", "include.path", expectedIncludePath) }
        .result
        .get()
    }
  }
}

apiValidation {
  ignoredProjects += buildList {
    add("compiler")
    add("metro-common")
    add("graph-viewer")
    add("compiler-tests")
    add("compiler-compat")
    add("latest")
    layout.projectDirectory.dir("compiler-compat").asFile.listFiles()!!.forEach {
      if (it.isDirectory && it.name.startsWith("k") && File(it, "version.txt").exists()) {
        add(it.name)
      }
    }
  }
  ignoredPackages += metroApiIgnoredPackages
  nonPublicMarkers += metroApiNonPublicMarkers
  @OptIn(ExperimentalBCVApi::class)
  klib {
    // This is only really possible to run on macOS
    // strictValidation = true
    enabled = true
  }
}

dokka {
  dokkaPublications.html {
    // NOTE: This path must be in sync with `mkdocs.yml`'s API nav config path
    outputDirectory.set(rootDir.resolve("docs/api"))
    includes.from(project.layout.projectDirectory.file("README.md"))
  }
}

tasks.register("installForFunctionalTest") {
  description = "Publishes all Metro artifacts to build/functionalTestRepo"
}

tasks.register<Sync>("prepareGraphViewer") {
  description = "Builds the browser graph viewer for the docs site"
  group = "documentation"
  dependsOn(":graph-viewer:jsBrowserProductionLibraryDistribution")
  into(layout.projectDirectory.dir("docs/graph-viewer"))
  from(layout.projectDirectory.dir("graph-viewer/build/dist/js/productionLibrary")) {
    include("*.mjs", "*.mjs.map")
  }
  from(layout.projectDirectory.dir("graph-viewer/src/host"))
  from(
    layout.projectDirectory.dir(
      "gradle-plugin/src/main/resources/dev/zacsweers/metro/gradle/analysis"
    )
  ) {
    include("graph-viewer.html", "graph-viewer.css", "graph-viewer.js")
  }
  from(layout.projectDirectory.file("design/pluginIcon_dark.svg"))
}

subprojects {
  apply(plugin = "metro.base")
  group = project.property("GROUP") as String
  version = project.property("VERSION_NAME") as String
}

dependencies {
  dokka(project(":gradle-plugin"))
  dokka(project(":interop-dagger"))
  dokka(project(":interop-guice"))
  dokka(project(":interop-jakarta"))
  dokka(project(":interop-javax"))
  dokka(project(":metro-trace"))
  dokka(project(":metrox-android"))
  dokka(project(":metrox-viewmodel"))
  dokka(project(":metrox-viewmodel-compose"))
  dokka(project(":runtime"))
  dokka(project(":runtime-coroutines"))
}
