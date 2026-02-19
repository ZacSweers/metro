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

internal val UNINITIALIZED = Any()

/**
 * A [Lazy] and [Provider] implementation that memoizes the value returned from a delegate
 * [Provider]. The provider instance is released after it's called.
 *
 * Platform-specific synchronization is provided by the [Lock] superclass (`ReentrantLock` on JVM,
 * reentrant spinlock on native, no-op on JS/Wasm).
 *
 * Ported from Dagger's `DoubleCheck` with modifications for KMP support.
 */
public abstract class BaseDoubleCheck<T>(provider: Provider<T>) : Provider<T>, Lazy<T> {
  private var provider: Provider<T>? = provider
  @Volatile private var _value: Any? = UNINITIALIZED
  private val lock = Lock()

  override val value: T
    get() {
      val result1 = _value
      if (result1 !== UNINITIALIZED) {
        @Suppress("UNCHECKED_CAST")
        return result1 as T
      }

      return locked(lock) {
        val result2 = _value
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

  override fun isInitialized(): Boolean = _value !== UNINITIALIZED

  override fun invoke(): T = value

  override fun toString(): String =
    if (isInitialized()) {
      "DoubleCheck(value=$_value)"
    } else {
      "DoubleCheck(value=<not initialized>)"
    }
}

/**
 * Checks to see if creating the new instance has resulted in a recursive call. If it has, and the
 * new instance is the same as the current instance, return the instance. However, if the new
 * instance differs from the current instance, an [IllegalStateException] is thrown.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun reentrantCheck(currentInstance: Any?, newInstance: Any?): Any? {
  val isReentrant = currentInstance !== UNINITIALIZED
  check(!isReentrant || currentInstance == newInstance) {
    "Scoped provider was invoked recursively returning different results: $currentInstance & $newInstance. This is likely due to a circular dependency."
  }
  return newInstance
}
