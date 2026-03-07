// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.jmh)
}

dependencies {
  // Compile against the original component (for Kotlin metadata)
  jmhCompileOnly(project(":app:component"))
  // Run against the minified jar
  jmhRuntimeOnly(project(":startup-jvm:minified-jar"))
  // Runtime dependencies not included in the minified jar (library classpath)
  jmhRuntimeOnly("dev.zacsweers.metro:runtime:+")
  jmhRuntimeOnly("javax.inject:javax.inject:1")
  jmhRuntimeOnly(libs.dagger.runtime)
  jmhRuntimeOnly(libs.kotlinInject.runtime)
}

jmh {
  warmupIterations = 4
  iterations = 10
  fork = 2
  resultFormat = "JSON"
}
