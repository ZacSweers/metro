// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
    optIn.addAll(
      "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
      "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
    )
  }
}

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-compiler:2.3.0-dev-7984")
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
}
