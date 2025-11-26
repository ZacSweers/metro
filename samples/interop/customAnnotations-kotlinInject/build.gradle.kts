// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.multiplatform)
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
  interop {
    includeKotlinInject()
    includeAnvilForKotlinInject()
  }
}
