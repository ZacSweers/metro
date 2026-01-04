// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ScatterMap
import androidx.collection.ScatterSet
import androidx.collection.emptyScatterSet

@Suppress("UNCHECKED_CAST")
internal class StringGraph(
  newBindingStack: () -> StringBindingStack,
  newBindingStackEntry:
    StringBindingStack.(
      contextKey: StringContextualTypeKey,
      binding: StringBinding?,
      roots: ScatterMap<StringContextualTypeKey, StringBindingStack.Entry>,
    ) -> StringBindingStack.Entry,
  /**
   * Creates a binding for keys not necessarily manually added to the graph (e.g.,
   * constructor-injected types).
   */
  computeBinding:
    (
      contextKey: StringContextualTypeKey,
      currentBindings: ScatterMap<StringTypeKey, StringBinding>,
      stack: StringBindingStack,
    ) -> ScatterSet<StringBinding> =
    { _, _, _ ->
      emptyScatterSet()
    },
) :
  MutableBindingGraph<
    String,
    StringTypeKey,
    StringContextualTypeKey,
    StringBinding,
    StringBindingStack.Entry,
    StringBindingStack,
  >(newBindingStack, newBindingStackEntry, computeBinding) {
  fun tryPut(binding: StringBinding) {
    tryPut(binding, StringBindingStack("AppGraph"))
  }
}
