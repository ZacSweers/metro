// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionProviderTest {

  @DependencyGraph
  abstract class FunctionProviderAccessorGraph {
    var counter = 0

    abstract val stringProvider: () -> String
    abstract val intProvider: () -> Int

    @Provides fun provideString(): String = "Hello, world!"

    @Provides fun provideInt(): Int = counter++
  }

  @Test
  fun `function provider as accessor`() {
    val graph = createGraph<FunctionProviderAccessorGraph>()
    assertEquals("Hello, world!", graph.stringProvider())
    assertEquals("Hello, world!", graph.stringProvider())
    assertEquals(0, graph.intProvider())
    assertEquals(1, graph.intProvider())
  }

  @DependencyGraph
  abstract class FunctionProviderInjectedGraph {
    var counter = 0

    abstract val consumer: FunctionConsumer

    @Provides fun provideString(): String = "Hello, world!"

    @Provides fun provideInt(): Int = counter++
  }

  @Inject class FunctionConsumer(val stringProvider: () -> String, val intProvider: () -> Int)

  @Test
  fun `function provider as injected param`() {
    val graph = createGraph<FunctionProviderInjectedGraph>()
    val consumer = graph.consumer
    assertEquals("Hello, world!", consumer.stringProvider())
    assertEquals("Hello, world!", consumer.stringProvider())
    assertEquals(0, consumer.intProvider())
    assertEquals(1, consumer.intProvider())
  }

  @DependencyGraph
  abstract class MixedProviderGraph {
    var counter = 0

    abstract val metroProvider: Provider<Int>
    abstract val functionProvider: () -> Int
    abstract val lazyInt: Lazy<Int>

    @Provides fun provideInt(): Int = counter++
  }

  @Test
  fun `function provider mixed with Provider and Lazy`() {
    val graph = createGraph<MixedProviderGraph>()
    // Both Provider and () -> T act as providers
    assertEquals(0, graph.metroProvider())
    assertEquals(1, graph.functionProvider())
    assertEquals(2, graph.metroProvider())
    assertEquals(3, graph.functionProvider())
    // Lazy is cached once captured
    val lazyInt = graph.lazyInt
    assertEquals(4, lazyInt.value)
    assertEquals(4, lazyInt.value)
  }

  @DependencyGraph(AppScope::class)
  abstract class ScopedFunctionProviderGraph {
    var counter = 0

    abstract val intProvider: () -> Int
    abstract val consumer: ScopedFunctionConsumer

    @SingleIn(AppScope::class) @Provides fun provideInt(): Int = counter++
  }

  @Inject class ScopedFunctionConsumer(val intProvider: () -> Int)

  @Test
  fun `function provider with scoped binding`() {
    val graph = createGraph<ScopedFunctionProviderGraph>()
    // Scoped binding should always return the same value
    assertEquals(0, graph.intProvider())
    assertEquals(0, graph.intProvider())
    assertEquals(0, graph.intProvider())
    // Injected consumer should also see the scoped value
    val consumer = graph.consumer
    assertEquals(0, consumer.intProvider())
    assertEquals(0, consumer.intProvider())
  }

  // -- Multibound set --

  @DependencyGraph
  interface MultiboundSetGraph {
    val strings: Set<String>
    val stringProvider: () -> Set<String>
    val consumer: SetConsumer

    @Provides @IntoSet fun provideA(): String = "a"

    @Provides @IntoSet fun provideB(): String = "b"
  }

  @Inject class SetConsumer(val stringProvider: () -> Set<String>)

  @Test
  fun `function provider with multibound set`() {
    val graph = createGraph<MultiboundSetGraph>()
    val expected = setOf("a", "b")
    assertEquals(expected, graph.strings)
    assertEquals(expected, graph.stringProvider())
    assertEquals(expected, graph.consumer.stringProvider())
  }

  // -- Provider of Lazy --

  @DependencyGraph
  abstract class ProviderOfLazyGraph {
    var counter = 0

    abstract val lazyProvider: () -> Lazy<Int>
    abstract val consumer: LazyProviderConsumer

    @Provides fun provideInt(): Int = counter++
  }

  @Inject class LazyProviderConsumer(val lazyProvider: () -> Lazy<Int>)

  @Test
  fun `function provider of lazy`() {
    val graph = createGraph<ProviderOfLazyGraph>()
    // Each call should return a new Lazy
    val lazy1 = graph.lazyProvider()
    val lazy2 = graph.lazyProvider()
    assertEquals(0, lazy1.value)
    assertEquals(0, lazy1.value)
    assertEquals(1, lazy2.value)
    assertEquals(1, lazy2.value)
    // Injected consumer should behave the same
    val lazy3 = graph.consumer.lazyProvider()
    assertEquals(2, lazy3.value)
    assertEquals(2, lazy3.value)
  }

  // -- Generics --

  @DependencyGraph
  interface GenericFunctionProviderGraph {
    val stringHolder: TypedHolder<String>
    val intHolder: TypedHolder<Int>

    @Provides fun provideString(): String = "Hello"

    @Provides fun provideInt(): Int = 42
  }

  @Inject class TypedHolder<T>(val provider: () -> T)

  @Test
  fun `function provider with generics`() {
    val graph = createGraph<GenericFunctionProviderGraph>()
    assertEquals("Hello", graph.stringHolder.provider())
    assertEquals(42, graph.intHolder.provider())
  }

  // -- Multibound map --

  @DependencyGraph
  interface MultiboundMapGraph {
    val map: Map<String, Int>
    val mapProvider: () -> Map<String, Int>
    val consumer: MapConsumer

    @Provides @IntoMap @StringKey("one") fun provideOne(): Int = 1

    @Provides @IntoMap @StringKey("two") fun provideTwo(): Int = 2
  }

  @Inject class MapConsumer(val mapProvider: () -> Map<String, Int>)

  @Test
  fun `function provider with multibound map`() {
    val graph = createGraph<MultiboundMapGraph>()
    val expected = mapOf("one" to 1, "two" to 2)
    assertEquals(expected, graph.map)
    assertEquals(expected, graph.mapProvider())
    assertEquals(expected, graph.consumer.mapProvider())
  }

  // -- Map<K, () -> V> --

  @DependencyGraph
  abstract class MultiboundMapOfFunctionsGraph {
    var counter = 0

    abstract val map: Map<String, () -> Int>
    abstract val consumer: MapFunctionConsumer

    @Provides @IntoMap @StringKey("counter") fun provideCounter(): Int = counter++

    @Provides @IntoMap @StringKey("fixed") fun provideFixed(): Int = 42
  }

  @Inject class MapFunctionConsumer(val map: Map<String, () -> Int>)

  @Test
  fun `function provider with multibound map of functions`() {
    val graph = createGraph<MultiboundMapOfFunctionsGraph>()
    assertEquals(0, graph.map["counter"]!!())
    assertEquals(1, graph.map["counter"]!!())
    assertEquals(42, graph.map["fixed"]!!())
    val consumer = graph.consumer
    assertEquals(2, consumer.map["counter"]!!())
    assertEquals(42, consumer.map["fixed"]!!())
  }
}
