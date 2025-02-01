// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.atomicfu)
  id("dev.zacsweers.metro")
}

kotlin {
  jvm()

  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.kotlinInject.runtime)
        implementation(libs.kotlinInject.anvil.runtime)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
  }
}

metro {
  customAnnotations {
    includeKotlinInject()
    includeAnvil()
  }
}
