// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

@Suppress("UNCHECKED_CAST")
internal class StringGraph(
  newBindingStack: () -> StringBindingStack,
  newBindingStackEntry: StringBindingStack.(binding: StringBinding) -> StringBindingStack.Entry,
  /**
   * Creates a binding for keys not necessarily manually added to the graph (e.g.,
   * constructor-injected types).
   */
  computeBinding: (key: StringTypeKey) -> StringBinding? = { null },
) :
  BindingGraph<
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
        BaseBinding<String, StringTypeKey, StringContextualTypeKey>
      ) -> StringBindingStack.Entry,
    computeBinding,
  ) {
  fun tryPut(binding: BaseBinding<String, StringTypeKey, StringContextualTypeKey>) {
    tryPut(binding, StringBindingStack("AppGraph"))
  }
}
