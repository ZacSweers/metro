// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/** Calls [ensureActive] before each value, preserving iteration order. */
internal inline fun <T> Iterable<T>.forEachCancellable(
  ensureActive: () -> Unit,
  action: (T) -> Unit,
) {
  for (value in this) {
    ensureActive()
    action(value)
  }
}

/** Keeps integer traversal unboxed and calls [ensureActive] before each value. */
internal inline fun IntProgression.forEachCancellable(
  ensureActive: () -> Unit,
  action: (Int) -> Unit,
) {
  for (value in this) {
    ensureActive()
    action(value)
  }
}
