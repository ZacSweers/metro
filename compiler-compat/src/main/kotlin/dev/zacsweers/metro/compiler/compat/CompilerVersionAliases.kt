// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

public object CompilerVersionAliases {
  /**
   * Resolves a compiler version through alias mappings.
   *
   * User-provided aliases take priority over built-in aliases. If no alias matches, the original
   * version is returned unchanged.
   *
   * This is primarily used to map fake IDE compiler versions (e.g., Android Studio canary builds
   * reporting `2.3.255-dev-255`) to their real compiler versions.
   */
  public fun resolve(
    version: KotlinToolingVersion,
    userAliases: Map<String, String> = emptyMap(),
  ): KotlinToolingVersion {
    val versionString = version.toString()
    // User aliases take priority
    userAliases[versionString]?.let {
      return KotlinToolingVersion(it)
    }
    // Then check built-in aliases
    BUILT_IN_COMPILER_VERSION_ALIASES[versionString]?.let {
      return KotlinToolingVersion(it)
    }
    return version
  }
}
