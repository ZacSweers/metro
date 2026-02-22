// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A simple class that produces instances of [T]. */
public actual fun interface Provider<T> {
  public actual operator fun invoke(): T
}
