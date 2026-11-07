// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.Locale

/** Controls where Metro generates graph creators, factory helpers, and member-injection helpers. */
public enum class CompanionMode {
  /**
   * Generates graph creators and helpers on companion objects. A graph's companion implements its
   * factory interface or exposes `factory()`. This is the default.
   */
  COMPANION_OBJECT,
  /** Generates graph creators and helpers as receiverless companion-block functions. */
  COMPANION_BLOCK,
  /** Keeps the companion-object APIs and adds companion-block functions that delegate to them. */
  COMPATIBILITY,
  /** Omits graph creators. Factory and member-injection helpers stay on companion objects. */
  NONE;

  /** The spelling users pass to the `companion-mode` option, like `companion-block`. */
  public val cliValue: String
    get() = name.lowercase(Locale.US).replace('_', '-')

  /** Whether the graph's companion object gets graph creators. */
  public val generatesCompanionCreators: Boolean
    get() = this == COMPANION_OBJECT || this == COMPATIBILITY

  /** Whether the graph gets receiverless graph creators. */
  public val generatesBlockCreators: Boolean
    get() = this == COMPANION_BLOCK || this == COMPATIBILITY

  /** Whether factory and member-injection helpers are receiverless functions on their class. */
  public val usesStaticHelpers: Boolean
    get() = this == COMPANION_BLOCK

  /** Whether receiverless helpers delegate to the companion-object helpers. */
  public val addsStaticHelperBridges: Boolean
    get() = this == COMPATIBILITY

  /** Whether this mode needs Kotlin's companion-blocks feature. */
  public val requiresCompanionBlocks: Boolean
    get() = this == COMPANION_BLOCK || this == COMPATIBILITY

  public companion object {
    /** Accepts enum names and [cliValue] spellings, ignoring case. */
    public fun parse(value: String): CompanionMode {
      val name = value.trim().replace('-', '_').uppercase(Locale.US)
      val mode = entries.firstOrNull { it.name == name }
      require(mode != null) {
        "Unknown companion-mode '$value'. Expected one of ${entries.joinToString { it.cliValue }}."
      }
      return mode
    }
  }
}
