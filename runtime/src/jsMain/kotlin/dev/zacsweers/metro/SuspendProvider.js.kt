// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A simple class that produces instances of [T] in a suspend context. */
@ExperimentalMetroCoroutinesApi
public actual fun interface SuspendProvider<T> {
  public actual suspend operator fun invoke(): T
}
