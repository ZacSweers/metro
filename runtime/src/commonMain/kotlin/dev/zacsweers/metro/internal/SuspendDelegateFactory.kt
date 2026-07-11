// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendProvider

/**
 * A [SuspendFactory] used to stitch SuspendProvider-indirection based dependency cycles, mirroring
 * [DelegateFactory] for non-suspend bindings.
 */
public class SuspendDelegateFactory<T> : SuspendFactory<T> {
  private var delegate: SuspendProvider<T>? = null

  override suspend fun invoke(): T {
    return checkNotNull(delegate)()
  }

  /**
   * Returns the factory's delegate.
   *
   * @throws NullPointerException if the delegate has not been set
   */
  public fun getDelegate(): SuspendProvider<T> {
    return checkNotNull(delegate)
  }

  public companion object {
    /**
     * Sets [delegateFactory]'s delegate provider to [delegate].
     *
     * [delegateFactory] must be an instance of [SuspendDelegateFactory], otherwise this method will
     * throw a [ClassCastException].
     */
    public fun <T> setDelegate(
      delegateFactory: SuspendProvider<T>,
      delegate: SuspendProvider<T>,
    ) {
      val asDelegateFactory = delegateFactory as SuspendDelegateFactory<T>
      setDelegateInternal(asDelegateFactory, delegate)
    }

    private fun <T> setDelegateInternal(
      delegateFactory: SuspendDelegateFactory<T>,
      delegate: SuspendProvider<T>,
    ) {
      check(delegateFactory.delegate == null)
      delegateFactory.delegate = delegate
    }
  }
}
