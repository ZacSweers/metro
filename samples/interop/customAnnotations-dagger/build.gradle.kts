// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.atomicfu)
  id("dev.zacsweers.metro")
}

metro {
  customAnnotations {
    includeDagger()
    includeAnvil()
  }
}

dependencies {
  implementation(libs.anvil.annotations)
  implementation(libs.dagger.runtime)
  testImplementation(libs.kotlin.test)
}
