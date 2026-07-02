// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

metroArtifact {
  artifactId.set("compiler-compat-k230")
  name.set("Metro Compiler Compat (Kotlin 2.3.0)")
}

dependencies {
  val kotlinVersion =
    providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() }
  compileOnly(kotlinVersion.map { "org.jetbrains.kotlin:kotlin-compiler:$it" })
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
}
