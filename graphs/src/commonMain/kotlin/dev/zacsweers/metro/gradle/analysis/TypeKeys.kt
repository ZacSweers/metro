// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import dev.zacsweers.metro.graph.ExperimentalMetroGraphApi

/**
 * Unwraps wrapper types from a type key to find the underlying type.
 *
 * For example:
 * - `Provider<com.example.Foo>` → `com.example.Foo`
 * - `Lazy<com.example.Bar>` → `com.example.Bar`
 * - `kotlin.collections.Set<com.example.Plugin>` → `kotlin.collections.Set<com.example.Plugin>`
 *   (collections are not unwrapped as they are the actual type)
 * - `com.example.Baz` → `com.example.Baz` (unchanged)
 */
@ExperimentalMetroGraphApi
public fun unwrapTypeKey(key: String): String {
  // Pattern for Provider<T> and Lazy<T> - these need to be unwrapped to find the target node
  val wrapperPrefixes =
    listOf(
      "Provider<",
      "Lazy<",
      "dev.zacsweers.metro.Provider<",
      "kotlin.Lazy<",
      "javax.inject.Provider<",
      "jakarta.inject.Provider<",
    )
  for (prefix in wrapperPrefixes) {
    if (key.startsWith(prefix) && key.endsWith(">")) {
      return key.removePrefix(prefix).removeSuffix(">")
    }
  }
  return key
}
