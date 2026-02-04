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
  public fun map(
    version: KotlinToolingVersion,
    userAliases: Map<String, String> = emptyMap(),
  ): KotlinToolingVersion {
    val versionString = version.toString()
    // User aliases take priority
    // Then check built-in aliases
    val alias = userAliases[versionString] ?: BUILT_IN_COMPILER_VERSION_ALIASES[versionString]
    return alias?.let(::KotlinToolingVersion) ?: version
  }
}
