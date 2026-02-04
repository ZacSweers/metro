// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xcontext-parameters", "-Xreturn-value-checker=full")

    optIn.addAll(
      "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
      "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
    )
  }
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
    // Known fake-to-real version mappings for IDE builds.
    // Android Studio canary builds report a fake version like "2.3.255-dev-255".
    // The real version can be found by checking the IntelliJ tag for the studio build number:
    // https://github.com/JetBrains/intellij-community/blob/idea/<intellij-version>/.idea/libraries/kotlinc_kotlin_compiler_common.xml
    """
    mapOf(
      // IntelliJ IDEA 2025.2.2 (eap)
      "2.2.20-ij252-17" to "2.2.20-dev-5812",
      // IntelliJ IDEA 2025.2.6.1 (stable)
      // IntelliJ IDEA 2025.2.4 (rc)
      "2.2.20-ij252-24" to "2.2.20-dev-5812",
      // Android Studio Otter 3 Feature Drop | 2025.2.3 (stable)
      // Android Studio Otter 3 Feature Drop | 2025.2.3 RC 3 (beta)
      // Android Studio Otter 3 Feature Drop | 2025.2.3 RC 2 (beta)
      // Android Studio Otter 3 Feature Drop | 2025.2.3 RC 1 (beta)
      // Android Studio Otter 2 Feature Drop | 2025.2.2 Patch 1 (stable)
      // Android Studio Otter 2 Feature Drop | 2025.2.2 (stable)
      // Android Studio Otter 2 Feature Drop | 2025.2.2 RC 2 (beta)
      // Android Studio Otter | 2025.2.1 Patch 1 (stable)
      // Android Studio Otter | 2025.2.1 (stable)
      "2.2.255-dev-255" to "2.2.20-dev-5812",
      // IntelliJ IDEA 2025.3.1 (rc)
      "2.3.20-ij253-45" to "2.3.20-dev-3964",
      // IntelliJ IDEA 2025.3.2 (stable)
      // IntelliJ IDEA 2025.3.2 (eap)
      "2.3.20-ij253-87" to "2.3.20-dev-3964",
      // Android Studio Panda 1 | 2025.3.1 RC 1 (beta)
      // Android Studio Panda 2 | 2025.3.2 Canary 2 (canary)
      // Android Studio Panda 2 | 2025.3.2 Canary 1 (canary)
      // Android Studio Panda 1 | 2025.3.1 Canary 5 (canary)
      // Android Studio Panda 1 | 2025.3.1 Canary 4 (canary)
      // Android Studio Panda 1 | 2025.3.1 Canary 3 (canary)
      "2.3.255-dev-255" to "2.3.20-dev-3964",
    )
    """
      .trimIndent(),
  )
}

dependencies {
  compileOnly(libs.kotlin.compiler)
  compileOnly(libs.kotlin.stdlib)

  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.truth)
}
