// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** Produces values of type [T] in a suspend context. */
@ExperimentalMetroCoroutinesApi
public actual fun interface SuspendProvider<T> : suspend () -> T {
  public actual override suspend operator fun invoke(): T
}
