// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion
import org.jetbrains.kotlin.tooling.core.isDev

internal object KotlinVersions {
  val kotlin230 = KotlinToolingVersion(2, 3, 0, null)
  val kotlin2320 = KotlinToolingVersion(2, 3, 20, null)
  val kotlin2320Beta1 = KotlinToolingVersion("2.3.20-Beta1")
  val kotlin2320Beta2 = KotlinToolingVersion("2.3.20-Beta2")
  val kotlin2320Dev6204 = KotlinToolingVersion("2.3.20-dev-6204")

  fun supportsTopLevelFirGen(version: KotlinToolingVersion): Boolean {
    return if (version.isDev) {
      version >= kotlin2320Dev6204
    } else {
      version >= kotlin2320Beta1
    }
  }
}
