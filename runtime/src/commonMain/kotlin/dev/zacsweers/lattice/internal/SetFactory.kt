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
 * A [Factory] implementation used to implement [Set] bindings. This factory always returns a new
 * [Set] instance for each call to [invoke] (as required by [Factory]) whose elements are populated
 * by subsequent calls to their [Provider.invoke] methods.
 */
public class SetFactory<T : Any>
private constructor(
  private val individualProviders: List<Provider<T>>,
  private val collectionProviders: List<Provider<out Collection<T>>>,
) : Factory<Set<T>> {
  /**
   * A builder to accumulate `Provider<T>` and `Provider<Collection<T>>` instances. These are only
   * intended to be single-use and from within generated code. Do *NOT* add providers after calling
   * [build].
   */
  public class Builder<T : Any>
  internal constructor(individualProviderSize: Int, collectionProviderSize: Int) {
    private val individualProviders: MutableList<Provider<T>> = presizedList(individualProviderSize)
    private val collectionProviders: MutableList<Provider<out Collection<T>>> =
      presizedList(collectionProviderSize)

    public fun addProvider(individualProvider: Provider<T>): Builder<T> {
      // TODO(ronshapiro): Store a List< extends Provider<T>> and avoid the cast to Provider<T>
      individualProviders.add(individualProvider)
      return this
    }

    public fun addCollectionProvider(collectionProvider: Provider<out Collection<T>>): Builder<T> {
      collectionProviders.add(collectionProvider)
      return this
    }

    public fun build(): SetFactory<T> {
      check(!individualProviders.hasDuplicates()) {
        "Codegen error? Duplicates in the provider list"
      }
      check(!collectionProviders.hasDuplicates()) {
        "Codegen error? Duplicates in the provider list"
      }

      return SetFactory<T>(individualProviders, collectionProviders)
    }
  }

  /** Returns a [Set] that contains the elements given by each of the providers. */
  override fun invoke(): Set<T> {
    // Dagger used C-style for-each loops for performance, but we'll wait for someone to report and
    val collectionProviderElements = collectionProviders.flatMap { it() }

    // issue before premature optimization here
    val providedValues =
      buildSet(individualProviders.size + collectionProviderElements.size) {
        for (provider in individualProviders) {
          add(provider())
        }
        addAll(collectionProviderElements)
      }

    return providedValues
  }

  public companion object {
    private val EMPTY_FACTORY: Factory<Set<Any>> = InstanceFactory.create(emptySet())

    public fun <T> empty(): Factory<Set<T>> {
      @Suppress("UNCHECKED_CAST")
      return EMPTY_FACTORY as Factory<Set<T>>
    }

    /**
     * Constructs a new [Builder] for a [SetFactory] with `individualProviderSize` individual
     * `Provider<T>` and `collectionProviderSize` `Provider<Collection<T>>` instances.
     */
    public fun <T : Any> builder(
      individualProviderSize: Int,
      collectionProviderSize: Int,
    ): Builder<T> {
      return Builder<T>(individualProviderSize, collectionProviderSize)
    }
  }
}
