// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

metroArtifact {
  artifactId.set("compiler-compat-k250_dev_3513")
  name.set("Metro Compiler Compat (Kotlin 2.5.0-dev-3513)")
}

dependencies {
  val kotlinVersion =
    providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() }
  compileOnly(kotlinVersion.map { "org.jetbrains.kotlin:kotlin-compiler:$it" })
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
  implementation(project(":compiler-compat:k250_dev_498"))
}
