// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.internal

/** Returns whether a Kotlin compiler version supports generating top-level declarations in FIR. */
public fun isTopLevelFirGenerationSupported(
  isDevVersion: Boolean,
  isAtLeast: (minimumVersion: String) -> Boolean,
): Boolean {
  val minimumVersion =
    if (isDevVersion) {
      "2.3.20-dev-6204"
    } else {
      "2.3.20-Beta1"
    }
  return isAtLeast(minimumVersion)
}
