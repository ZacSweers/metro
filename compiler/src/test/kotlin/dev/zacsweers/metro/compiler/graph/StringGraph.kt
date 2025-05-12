// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

@Suppress("UNCHECKED_CAST")
internal class StringGraph(
  newBindingStack: () -> StringBindingStack,
  newBindingStackEntry:
    StringBindingStack.(
      contextKey: StringContextualTypeKey,
      binding: StringBinding?,
      roots: Map<StringContextualTypeKey, StringBindingStack.Entry>,
    ) -> StringBindingStack.Entry,
  /**
   * Creates a binding for keys not necessarily manually added to the graph (e.g.,
   * constructor-injected types).
   */
  computeBinding: (contextKey: StringContextualTypeKey) -> StringBinding? = { _ -> null },
) :
  MutableBindingGraph<
    String,
    StringTypeKey,
    StringContextualTypeKey,
    BaseBinding<String, StringTypeKey, StringContextualTypeKey>,
    StringBindingStack.Entry,
    StringBindingStack,
  >(
    newBindingStack,
    newBindingStackEntry
      as
      StringBindingStack.(
        StringContextualTypeKey,
        BaseBinding<String, StringTypeKey, StringContextualTypeKey>?,
        Map<StringContextualTypeKey, StringBindingStack.Entry>,
      ) -> StringBindingStack.Entry,
    absentBinding = { StringBinding(it) },
    computeBinding,
  ) {
  fun tryPut(binding: BaseBinding<String, StringTypeKey, StringContextualTypeKey>) {
    tryPut(binding, StringBindingStack("AppGraph"))
  }
}
