/*
 * Copyright (C) 2016 The Dagger Authors.
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
package dev.zacsweers.lattice.internal

import dev.zacsweers.lattice.Provider

/** A [Lazy] and [Provider] implementation that memoizes the value returned from a delegate. */
// TODO inline the lazy impl?
public class DoubleCheck<T : Any> private constructor(provider: Provider<T>) :
  Provider<T>, Lazy<T> by lazy(LazyThreadSafetyMode.SYNCHRONIZED, { provider() }) {

  override fun invoke(): T = value

  public companion object {
    /** Returns a [Provider] that caches the value from the given delegate provider. */
    public fun <P : Provider<T>, T : Any> provider(delegate: P): Provider<T> {
      if (delegate is DoubleCheck<*>) {
        /*
         * This should be a rare case, but if we have a scoped @Binds that delegates to a scoped
         * binding, we shouldn't cache the value again.
         */
        return delegate
      }
      return DoubleCheck<T>(delegate)
    }

    /** Returns a [Lazy] that caches the value from the given provider. */
    public fun <P : Provider<T>, T : Any> lazy(provider: P): Lazy<T> {
      if (provider is Lazy<*>) {
        @Suppress("UNCHECKED_CAST") val lazy = provider as Lazy<T>
        // Avoids memoizing a value that is already memoized.
        // NOTE: There is a pathological case where Provider<P> may implement Lazy<L>, but P and L
        // are different types using covariant return on get(). Right now this is used with
        // DoubleCheck<T> exclusively, which is implemented such that P and L are always
        // the same, so it will be fine for that case.
        return lazy
      }
      return DoubleCheck<T>(provider)
    }
  }
}
