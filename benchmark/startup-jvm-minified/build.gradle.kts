// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.jmh)
}

dependencies {
  // Compile against the original component (for Kotlin metadata)
  jmhCompileOnly(project(":app:component"))
  // The minified jar contains the generated component and its selected framework runtime.
  jmhRuntimeOnly(project(":startup-jvm:minified-jar"))
}

configurations.named("jmhRuntimeClasspath") {
  // R8 already packages and optimizes the selected Kotlin runtime in the minified jar.
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

jmh {
  warmupIterations = 4
  iterations = 10
  fork = 2
  resultFormat = "JSON"
  jvmArgs =
    listOf(
      "-Dmetro.benchmark.runtimeTraceDir=${rootProject.layout.projectDirectory.dir("app/component/build/metro-runtime-traces").asFile.absolutePath}"
    )
}
