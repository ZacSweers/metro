// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

dependencies {
  api(libs.androidx.tracing)
  implementation(libs.androidx.tracing.wire)
}
