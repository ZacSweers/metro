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
 * A [Factory] implementation used to implement [Map] bindings. This factory returns a `Map<K, V>`
 * when calling [invoke] (as specified by [Factory]).
 */
public class MapFactory<K : Any, V : Any> private constructor(map: Map<K, Provider<V>>) :
  AbstractMapFactory<K, V, V>(map) {

  /**
   * Returns a `Map<K, V>` whose iteration order is that of the elements given by each of the
   * providers, which are invoked in the order given at creation.
   */
  override fun invoke(): Map<K, V> {
    val result = newLinkedHashMapWithExpectedSize<K, V>(contributingMap().size)
    for (entry in contributingMap().entries) {
      result.put(entry.key, entry.value())
    }
    return result.toUnmodifiableMap()
  }

  /** A builder for [MapFactory]. */
  public class Builder<K : Any, V : Any> internal constructor(size: Int) :
    AbstractMapFactory.Builder<K, V, V>(size) {

    public override fun put(key: K, providerOfValue: Provider<V>): Builder<K, V> = apply {
      super.put(key, providerOfValue)
    }

    override fun putAll(mapOfProviders: Provider<Map<K, V>>): Builder<K, V> = apply {
      super.putAll(mapOfProviders)
    }

    /** Returns a new [MapFactory]. */
    public fun build(): MapFactory<K, V> {
      return MapFactory(map)
    }
  }

  public companion object MapFactoryCompanion {
    private val EMPTY: Provider<Map<Any, Any>> = InstanceFactory.create(emptyMap())

    /** Returns a new [Builder] */
    public fun <K : Any, V : Any> builder(size: Int): Builder<K, V> {
      return Builder(size)
    }

    /** Returns a factory of an empty map. */
    public fun <K, V> emptyMapProvider(): Provider<Map<K, V>> {
      @Suppress("UNCHECKED_CAST") // safe contravariant cast
      return EMPTY as Provider<Map<K, V>>
    }
  }
}
