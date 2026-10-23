// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.SuspendCloseableGraph
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs cleanup in the caller's context even when its job is cancelled. */
public suspend fun closeSuspendGraph(
  state: GraphLifecycleState,
  graph: Any,
  parent: GraphLifecycleState?,
  cleanup: suspend (List<Any>) -> Unit,
) {
  val owned = state.claimChildren() ?: return
  try {
    withContext(NonCancellable) { cleanup(owned) }
  } finally {
    parent?.remove(graph)
  }
}

/** A suspend graph can own children with either cleanup contract. */
@OptIn(ExperimentalMetroApi::class)
public suspend fun closeSuspendChildren(owned: List<Any>) {
  for (child in owned) {
    if (child is SuspendCloseableGraph) {
      child.close()
    } else {
      (child as AutoCloseable).close()
    }
  }
}
