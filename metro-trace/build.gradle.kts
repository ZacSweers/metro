// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

dependencies {
  api(project(":runtime"))
  api(libs.androidx.tracing)
  implementation(libs.androidx.tracing.wire)
  testImplementation(libs.kotlin.testJunit5)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
