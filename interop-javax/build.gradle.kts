// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  id("metro.publish")
}

metroArtifact {
  artifactId.set("interop-javax")
  name.set("Metro Javax Interop")
}

dependencies {
  api(project(":runtime"))
  api(libs.javaxInject)
}
