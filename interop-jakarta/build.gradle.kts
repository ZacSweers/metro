// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

metroArtifact {
  artifactId.set("interop-jakarta")
  name.set("Metro Jakarta Interop")
}

dependencies {
  api(project(":runtime"))
  api(libs.jakartaInject)
}
