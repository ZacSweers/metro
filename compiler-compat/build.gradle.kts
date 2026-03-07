// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
}

buildConfig {
  packageName("dev.zacsweers.metro.compiler.compat")
  kotlin {
    useKotlinOutput {
      internalVisibility = true
      topLevelConstants = true
    }
  }
  buildConfigField(
    "kotlin.collections.Map<String, String>",
    "BUILT_IN_COMPILER_VERSION_ALIASES",
    providers
      .fileContents(layout.projectDirectory.file("ide-mappings.txt"))
      .asText
      // Known tags-to-real version mappings for IDE builds.
      // Android Studio canary builds report a fake version like "2.3.255-dev-255".
      // The real version can be found by checking the IntelliJ tag for the studio build number:
      // https://github.com/JetBrains/intellij-community/blob/idea/<intellij-version>/.idea/libraries/kotlinc_kotlin_compiler_common.xml
      .map { text ->
        text
          .lineSequence()
          .filter { it.isNotBlank() && !it.startsWith("#") }
          .joinToString(prefix = "mapOf(\n", postfix = "\n)", separator = "\n,") { line ->
            val (from, to) = line.split('=', limit = 2)
            "  \"$from\" to \"$to\""
          }
      },
  )
}

dependencies {
  compileOnly(libs.kotlin.compiler)
  compileOnly(libs.kotlin.stdlib)

  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.truth)
}
