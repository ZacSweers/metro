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
  // Runtime dependencies not included in the minified jar (library classpath).
  // All frameworks' runtimes are listed so the same minified-jar harness works regardless of
  // which framework the generator produced — the classloader will only resolve what's used.
  jmhRuntimeOnly("dev.zacsweers.metro:runtime:+")
  jmhRuntimeOnly("javax.inject:javax.inject:1")
  jmhRuntimeOnly(libs.dagger.runtime)
  jmhRuntimeOnly(libs.kotlinInject.runtime)
  jmhRuntimeOnly(libs.koin.core)
  jmhRuntimeOnly(libs.koin.annotations)
}

jmh {
  warmupIterations = 4
  iterations = 10
  fork = 2
  resultFormat = "JSON"
}
