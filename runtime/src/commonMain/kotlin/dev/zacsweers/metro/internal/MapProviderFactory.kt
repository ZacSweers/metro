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

/**
 * A [Factory] implementation used to implement [Map] bindings. This factory returns a `Map<K,
 * Provider<V>>` when calling [get] (as specified by [Factory]).
 */
public class MapProviderFactory<K : Any, V : Any>
private constructor(contributingMap: Map<K, Provider<V>>) :
  AbstractMapFactory<K, V, Provider<V>>(contributingMap), Lazy<Map<K, Provider<V>>> {
  /**
   * Returns a `Map<K, Provider<V>>` whose iteration order is that of the elements given by each of
   * the providers, which are invoked in the order given at creation.
   */
  override fun invoke(): Map<K, Provider<V>> = contributingMap()

  // TODO are these overrides right?
  override fun isInitialized(): Boolean = true

  override val value: Map<K, Provider<V>> = invoke()

  /** A builder for [MapProviderFactory]. */
  public class Builder<K : Any, V : Any> internal constructor(size: Int) :
    AbstractMapFactory.Builder<K, V, Provider<V>>(size) {
    public override fun put(key: K, providerOfValue: Provider<V>): Builder<K, V> = apply {
      super.put(key, providerOfValue)
    }

    public override fun putAll(mapOfProviders: Provider<Map<K, Provider<V>>>): Builder<K, V> =
      apply {
        super.putAll(mapOfProviders)
      }

    /** Returns a new [MapProviderFactory]. */
    public fun build(): MapProviderFactory<K, V> = MapProviderFactory<K, V>(map)
  }

  public companion object {
    /** Returns a new [Builder] */
    public fun <K : Any, V : Any> builder(size: Int): Builder<K, V> {
      return Builder<K, V>(size)
    }
  }
}
