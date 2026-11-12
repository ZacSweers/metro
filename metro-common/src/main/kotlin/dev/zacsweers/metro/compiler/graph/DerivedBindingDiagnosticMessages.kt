// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Derived binding diagnostic wording shared by the compiler and the IDE. The compiler/IDE parity
 * suite compares diagnostic titles, so both frontends must render these identically.
 */
public object DerivedBindingDiagnosticMessages {
  public const val DERIVED_BINDINGS_NOT_ENABLED: String =
    "Type parameters on `@Provides` and `@Binds` functions need derived bindings, which are " +
      "disabled. Enable the `enable-derived-bindings` compiler option or set " +
      "`metro.enableDerivedBindings` to true."

  /**
   * Reported once on a graph that collects generic providers or binds functions from dependencies
   * while derived bindings are disabled. [providerNames] are their rendered names in a stable
   * order.
   */
  public fun derivedBindingsNotEnabledForDependencies(providerNames: List<String>): String {
    val renderedNames = renderNames(providerNames)
    val verb =
      if (providerNames.size == 1) {
        "declares"
      } else {
        "declare"
      }
    return "This graph includes $renderedNames, which $verb type parameters. Generic providers " +
      "need derived bindings, which are disabled. Enable the `enable-derived-bindings` compiler " +
      "option or set `metro.enableDerivedBindings` to true."
  }

  /**
   * Reported when several generic providers match [key] and none is more specific than all the
   * others. [providerNames] are every matching provider's name in a stable order.
   */
  public fun ambiguousDerivedBinding(key: String, providerNames: List<String>): String {
    return "`$key` matches the generic providers ${renderNames(providerNames)}. None of them is " +
      "more specific than all the others. Add an exact binding for `$key`, or make one " +
      "provider's return type more specific."
  }

  /**
   * Reported when several generic providers match [key] and the most specific one wins.
   * [selectedProvider] is the winner. [providerNames] are every matching provider's name in a
   * stable order, including the winner.
   */
  public fun derivedBindingOverlap(
    key: String,
    selectedProvider: String,
    providerNames: List<String>,
  ): String {
    return "`$key` matches the generic providers ${renderNames(providerNames)}. Metro uses " +
      "`$selectedProvider` because its return type is the most specific."
  }

  /**
   * Reported when [provider] keeps requesting a larger key from itself. [chain] lists the keys it
   * produced on one derivation chain, oldest first. The last one is the key Metro rejected.
   */
  public fun divergentDerivedBinding(provider: String, chain: List<String>): String {
    val derived = chain.dropLast(1)
    val rejected = chain.last()
    return "The generic provider `$provider` keeps requesting a larger key, so derivation would " +
      "never end. It derived ${renderNames(derived)}, then needed `$rejected`. Check the " +
      "parameter types of `$provider`."
  }

  /**
   * Reported when deriving [key] would put more than [limit] derived bindings on one derivation
   * chain.
   */
  public fun derivedBindingDepthLimit(key: String, limit: Int): String {
    return "Deriving `$key` would put more than $limit derived bindings on one chain, which is " +
      "the limit. Add an exact binding for a key on the chain to shorten it."
  }

  /** Quotes [names] and joins them into a readable list. */
  private fun renderNames(names: List<String>): String {
    val quoted = names.map { "`$it`" }
    return when (quoted.size) {
      1 -> quoted.single()
      2 -> "${quoted[0]} and ${quoted[1]}"
      else -> quoted.dropLast(1).joinToString(", ") + ", and " + quoted.last()
    }
  }
}
