// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  val kotlinVersion =
    providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() }
  compileOnly(kotlinVersion.map { "org.jetbrains.kotlin:kotlin-compiler:$it" })
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
  implementation(project(":compiler-compat:k2320_dev_5706"))
}
