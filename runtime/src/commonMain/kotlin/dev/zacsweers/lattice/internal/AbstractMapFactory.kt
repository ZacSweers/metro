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
package dev.zacsweers.lattice.internal

import dev.zacsweers.lattice.Provider

/**
 * An `abstract` [Factory] implementation used to implement [Map] bindings.
 *
 * @param <K> the key type of the map that this provides
 * @param <V> the type that each contributing factory
 * @param <V2> the value type of the map that this provides </V2></V></K>
 */
public sealed class AbstractMapFactory<K : Any, V : Any, V2>(map: Map<K, Provider<V>>) :
  Factory<Map<K, V2>> {
  private val contributingMap: Map<K, Provider<V>> = map.toUnmodifiableMap()

  /** The map of [Provider]s that contribute to this map binding. */
  public fun contributingMap(): Map<K, Provider<V>> {
    return contributingMap
  }

  /** A builder for [AbstractMapFactory]. */
  public sealed class Builder<K : Any, V : Any, V2>(size: Int) {
    internal val map: LinkedHashMap<K, Provider<V>> = newLinkedHashMapWithExpectedSize(size)

    // Unfortunately, we cannot return a self-type here because a raw Provider type passed to one of
    // these methods affects the returned type of the method. The first put*() call erases the self
    // type to the "raw" self type, and the second erases the type to the upper bound
    // (AbstractMapFactory.Builder), which doesn't have a build() method.
    //
    // The methods are therefore not declared public so that each subtype will redeclare them and
    // expand their accessibility
    /** Associates `key` with `providerOfValue`. */
    public open fun put(key: K, providerOfValue: Provider<V>): Builder<K, V, V2> = apply {
      map.put(key, providerOfValue)
    }

    public open fun putAll(mapOfProviders: Provider<Map<K, V2>>): Builder<K, V, V2> = apply {
      if (mapOfProviders is DelegateFactory) {
        val asDelegateFactory: DelegateFactory<Map<K, V2>> = mapOfProviders
        return putAll(asDelegateFactory.getDelegate())
      }
      @Suppress("UNCHECKED_CAST")
      val asAbstractMapFactory = (mapOfProviders as AbstractMapFactory<K, V, *>)
      map.putAll(asAbstractMapFactory.contributingMap)
    }
  }
}
