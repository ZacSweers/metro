// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicReference

/** Holds child cleanup callbacks until the child or its parent closes. */
public class CloseCallbackRegistry<C : Any> {
  private val entries = AtomicReference<List<Entry<C>>>(emptyList())

  /** Registers [callback] under the identity of [key]. */
  public fun add(key: Any, callback: C) {
    while (true) {
      val current = entries.load()
      if (current.any { it.key === key }) {
        return
      }
      if (entries.compareAndSet(current, current + Entry(key, callback))) {
        return
      }
    }
  }

  /** Releases a child's callback after its independent cleanup attempt finishes. */
  public fun remove(key: Any) {
    while (true) {
      val current = entries.load()
      val remaining = current.filterNot { it.key === key }
      if (remaining.size == current.size) {
        return
      }
      if (entries.compareAndSet(current, remaining)) {
        return
      }
    }
  }

  /** Takes the callbacks and releases this registry's references to their children. */
  public fun drain(): List<Entry<C>> = entries.exchange(emptyList())

  /** Retains the child while its captured cleanup attempt runs. */
  public class Entry<C>(public val key: Any, public val callback: C)
}
