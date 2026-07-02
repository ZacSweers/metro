// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator

/**
 * Strategy for naming generated private members (provider/instance/factory backing fields and
 * properties) in graph, factory, and members-injector classes.
 *
 * Selected per generated class. See `IrGraphGenerator` for the graph-size-keyed selection of
 * [Descriptive], [Typed], or [Minimal].
 */
internal sealed interface MemberNamer {
  /**
   * Returns the base identifier suggestion for an allocator. [descriptive] is invoked only when
   * needed (for [Descriptive]); other implementations ignore it.
   */
  fun suggest(kind: Kind, descriptive: () -> String): String

  enum class Kind {
    PROVIDER,
    INSTANCE,
    FACTORY,
  }

  /** Uses the caller-supplied descriptive name. */
  data object Descriptive : MemberNamer {
    override fun suggest(kind: Kind, descriptive: () -> String): String = descriptive()
  }

  /** Short typed prefixes: `provider`, `instance`, `factory`. */
  data object Typed : MemberNamer {
    override fun suggest(kind: Kind, descriptive: () -> String): String =
      when (kind) {
        Kind.PROVIDER -> "provider"
        Kind.INSTANCE -> "instance"
        Kind.FACTORY -> "factory"
      }
  }

  /** Single short vocabulary; all kinds collapse to `provider`. */
  data object Minimal : MemberNamer {
    override fun suggest(kind: Kind, descriptive: () -> String): String = "provider"
  }
}

/** Allocates a unique name from a [MemberNamer] suggestion. */
internal fun NameAllocator.allocateName(
  namer: MemberNamer,
  kind: MemberNamer.Kind,
  descriptive: () -> String,
): String = newName(namer.suggest(kind, descriptive))
