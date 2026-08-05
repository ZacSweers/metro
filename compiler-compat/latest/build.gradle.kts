// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  `java-library`
  id("metro.publish")
}

metroArtifact {
  artifactId.set("compiler-compat-latest")
  name.set("Metro Compiler Compat (Latest)")
}

val compatDirectory = rootProject.isolated.projectDirectory.dir("compiler-compat")

val availableModules =
  compatDirectory.asFile
    .listFiles()!!
    .filter { directory ->
      directory.isDirectory &&
        directory.name.startsWith("k") &&
        directory.resolve("version.txt").exists()
    }
    .associateBy { directory -> directory.resolve("version.txt").readText().trim() }

val versionsInReleaseOrder =
  compatDirectory.file("version-aliases.txt").asFile.readLines().map(String::trim).filter { line ->
    line.isNotEmpty() && !line.startsWith("#")
  }

val latestVersionWithModule =
  checkNotNull(versionsInReleaseOrder.lastOrNull(availableModules::containsKey)) {
    "No versioned compiler compatibility modules found"
  }

val latestModule = availableModules.getValue(latestVersionWithModule)

dependencies { api(project(":compiler-compat:${latestModule.name}")) }
