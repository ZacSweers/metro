/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider

/** A DelegateFactory that is used to stitch Provider/Lazy indirection based dependency cycles. */
public class DelegateFactory<T : Any> : Factory<T> {
  private var delegate: Provider<T>? = null

  override fun invoke(): T {
    return checkNotNull(delegate)()
  }

  /**
   * Returns the factory's delegate.
   *
   * @throws NullPointerException if the delegate has not been set
   */
  public fun getDelegate(): Provider<T> {
    return checkNotNull(delegate)
  }

  public companion object {
    /**
     * Sets [delegateFactory]'s delegate provider to [delegate].
     *
     * [delegateFactory] must be an instance of [DelegateFactory], otherwise this method will throw
     * a [ClassCastException].
     */
    public fun <T : Any> setDelegate(delegateFactory: Provider<T>, delegate: Provider<T>) {
      val asDelegateFactory = delegateFactory as DelegateFactory<T>
      setDelegateInternal<T>(asDelegateFactory, delegate)
    }

    private fun <T : Any> setDelegateInternal(
      delegateFactory: DelegateFactory<T>,
      delegate: Provider<T>,
    ) {
      check(delegateFactory.delegate == null)
      delegateFactory.delegate = delegate
    }
  }
}
