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

/** A [Provider] of [Lazy] instances that each delegate to a given [Provider]. */
public class ProviderOfLazy<T> private constructor(private val provider: Provider<T>) :
  Provider<Lazy<T>> {

  /**
   * Returns a new instance of [Lazy<T>][Lazy], which calls [Provider.invoke] at most once on the
   * [Provider] held by this object.
   */
  override fun invoke(): Lazy<T> = DoubleCheck.lazy(provider)

  public companion object {
    /**
     * Creates a new [Provider<Lazy<T>>][Provider] that decorates the given [provider].
     *
     * @see invoke
     */
    public fun <T> create(provider: Provider<T>): Provider<Lazy<T>> = ProviderOfLazy(provider)
  }
}
