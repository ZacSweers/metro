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
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal val UNINITIALIZED = Any()

/**
 * A [Lazy] and [Provider] implementation that memoizes the value returned from a [provider]. The
 * [provider] instance is released after it's called.
 *
 * Modification notes:
 * - Some semantics from the kotlin stdlib's synchronized [Lazy] impl are ported to here.
 * - Uses AtomicFu's [synchronized] + [SynchronizedObject] APIs for KMP support.
 * - The [_value] will eagerly return if initialized
 * - [_value] is [@Volatile][Volatile]
 */
public class DoubleCheck<T : Any> private constructor(provider: Provider<T>) :
  SynchronizedObject(), Provider<T>, Lazy<T> {

  private var provider: Provider<T>? = provider
  @Volatile private var _value: Any? = UNINITIALIZED

  override val value: T
    get() {
      var result1 = _value
      if (result1 !== UNINITIALIZED) {
        @Suppress("UNCHECKED_CAST")
        return result1 as T
      }

      return synchronized(this) {
        var result2 = _value
        if (result2 !== UNINITIALIZED) {
          @Suppress("UNCHECKED_CAST") (result2 as T)
        } else {
          val typedValue = provider!!()
          _value = reentrantCheck(_value, typedValue)
          // Null out the reference to the provider. We are never going to need it again, so we
          // can make it eligible for GC.
          provider = null
          typedValue
        }
      }
    }

  override fun isInitialized(): Boolean = _value === UNINITIALIZED

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

    /**
     * Checks to see if creating the new instance has resulted in a recursive call. If it has, and
     * the new instance is the same as the current instance, return the instance. However, if the
     * new instance differs from the current instance, an [IllegalStateException] is thrown.
     */
    private fun reentrantCheck(currentInstance: Any?, newInstance: Any?): Any? {
      val isReentrant = currentInstance != UNINITIALIZED
      check(!isReentrant || currentInstance == newInstance) {
        "Scoped provider was invoked recursively returning different results: $currentInstance & $newInstance. This is likely due to a circular dependency."
      }
      return newInstance
    }
  }
}
