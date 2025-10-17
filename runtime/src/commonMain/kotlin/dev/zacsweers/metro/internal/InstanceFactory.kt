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

import dev.zacsweers.metro.internal.InstanceFactory.Companion.invoke


/**
 * A [Factory] implementation that returns a single instance for all invocations of [invoke].
 *
 * Note that while this is a [Factory] implementation, and thus unscoped, each call to [invoke] will
 * always return the same instance. As such, any scoping applied to this factory is redundant and
 * unnecessary. However, using this with [DoubleCheck.provider] is valid and may be desired for
 * testing or contractual guarantees.
 */
public class InstanceFactory<T> private constructor(override val value: T) : Factory<T>, Lazy<T> {

  override fun isInitialized(): Boolean = true

  public override fun invoke(): T = value

  override fun toString(): String = value.toString()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || this::class != other::class) return false

    other as InstanceFactory<*>

    return value == other.value
  }

  override fun hashCode(): Int {
    return value?.hashCode() ?: 0
  }

  public companion object {
    private val EMPTY = InstanceFactory<Any?>(null)

    public operator fun <T> invoke(value: T): InstanceFactory<T> {
      return if (value == null) {
        @Suppress("UNCHECKED_CAST")
        EMPTY as InstanceFactory<T>
      } else {
        InstanceFactory(value)
      }
    }
  }
}
