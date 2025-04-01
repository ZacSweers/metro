// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.zacsweers.metro")
  application
}

application {
  mainClass.set("dev.zacsweers.metro.sample.multimodule.app.MainKt")
}

dependencies {
  implementation(project(":multi-module-test:common"))
  implementation(project(":multi-module-test:parent-graph"))
  implementation(project(":multi-module-test:child-graph"))
  implementation(project(":multi-module-test:contributor"))
  implementation(project(":multi-module-test:aggregator"))
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}