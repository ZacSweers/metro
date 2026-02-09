// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

public object CompilerVersionAliases {
  /**
   * Sentinel value in alias mappings indicating a version that only works in CLI (Gradle) builds,
   * not in IDE (IntelliJ/Android Studio) FIR integration. When [map] encounters this value, it
   * returns `null` to signal that the caller should skip enabling FIR extensions.
   */
  public const val CLI_ONLY: String = "CLI_ONLY"

  /**
   * Resolves a compiler version through alias mappings.
   *
   * User-provided aliases take priority over built-in aliases. If no alias matches, the original
   * version is returned unchanged.
   *
   * Returns `null` if the version is mapped to [CLI_ONLY], indicating it should not be used for IDE
   * FIR integration.
   *
   * This is primarily used to map fake IDE compiler versions (e.g., Android Studio canary builds
   * reporting `2.3.255-dev-255`) to their real compiler versions.
   */
  public fun map(
    version: KotlinToolingVersion,
    userAliases: Map<String, String> = emptyMap(),
  ): KotlinToolingVersion? {
    val versionString = version.toString()
    // User aliases take priority
    // Then check built-in aliases
    val alias = userAliases[versionString] ?: BUILT_IN_COMPILER_VERSION_ALIASES[versionString]
    return when (alias) {
      null -> version // No alias found, return original
      CLI_ONLY -> null // Explicitly marked as CLI-only
      else -> KotlinToolingVersion(alias)
    }
  }
}
