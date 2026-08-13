// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import dev.zacsweers.metro.compiler.internal.isTopLevelFirGenerationSupported
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion
import org.jetbrains.kotlin.tooling.core.isDev

internal object KotlinVersions {
  val kotlin230 = KotlinToolingVersion(2, 3, 0, null)
  val kotlin2320 = KotlinToolingVersion(2, 3, 20, null)
  val kotlin2320Beta2 = KotlinToolingVersion("2.3.20-Beta2")
  val kotlin2420Beta1 = KotlinToolingVersion("2.4.20-Beta1")
  val kotlin2420Dev6138 = KotlinToolingVersion("2.4.20-dev-6138")

  fun supportsTopLevelFirGen(version: KotlinToolingVersion): Boolean {
    return isTopLevelFirGenerationSupported(version.isDev) { minimumVersion ->
      version >= KotlinToolingVersion(minimumVersion)
    }
  }

  fun supportsIrClassGeneration(version: KotlinToolingVersion): Boolean {
    return version >= kotlin2420Dev6138
  }

  fun supportsPrivateProviderProperties(version: KotlinToolingVersion): Boolean {
    return version >= kotlin2420Beta1
  }
}
