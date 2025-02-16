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
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider

/**
 * @see [BaseDoubleCheck]
 */
public class DoubleCheck<T : Any> private constructor(provider: Provider<T>) :
  BaseDoubleCheck<T>(provider) {

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
