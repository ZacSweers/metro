// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

metroArtifact {
  artifactId.set("interop-dagger")
  name.set("Metro Dagger Inteorp")
}

dependencies {
  api(project(":runtime"))
  api(project(":interop-javax"))
  api(project(":interop-jakarta"))
  api(libs.dagger.runtime)
}
