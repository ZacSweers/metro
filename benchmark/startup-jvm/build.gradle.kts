// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.jmh)
}

dependencies { jmh(project(":app:component")) }

jmh {
  warmupIterations = 3
  iterations = 5
  fork = 2
  resultFormat = "JSON"
}
