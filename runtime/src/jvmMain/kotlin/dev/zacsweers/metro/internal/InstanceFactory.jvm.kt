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

@JvmInline
public actual value class InstanceFactory<T : Any> private constructor(override val value: T) : Factory<T>, Lazy<T> {
  actual override fun isInitialized(): Boolean = true

  public actual override fun invoke(): T = value

  actual override fun toString(): String = value.toString()

  public actual companion object {
    public actual fun <T : Any> create(instance: T): Factory<T> {
      return InstanceFactory(instance)
    }
  }
}
