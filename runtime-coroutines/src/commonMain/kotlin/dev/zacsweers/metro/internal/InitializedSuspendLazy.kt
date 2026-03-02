// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendLazy

public class InitializedSuspendLazy<T>(private val value: T) : SuspendLazy<T> {
  override suspend fun value(): T = value

  override fun isInitialized(): Boolean = true

  override fun toString(): String = value.toString()
}
