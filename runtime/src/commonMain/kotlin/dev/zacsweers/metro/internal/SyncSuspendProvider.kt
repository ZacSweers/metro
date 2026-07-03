// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SuspendProvider
import kotlin.jvm.JvmInline

/**
 * A [SuspendProvider] that resolves synchronously by delegating to a regular [Provider].
 *
 * Used by Metro-generated code to satisfy `SuspendProvider<T>` slots (e.g. suspend factory ctor
 * params) when the underlying graph holds a regular [Provider]. Allocation-free thanks to
 * `@JvmInline`, so this is just a typed view over the existing provider.
 */
@JvmInline
public value class SyncSuspendProvider<T : Any>(private val delegate: Provider<T>) :
  SuspendProvider<T> {
  override suspend fun invoke(): T = delegate()
}
