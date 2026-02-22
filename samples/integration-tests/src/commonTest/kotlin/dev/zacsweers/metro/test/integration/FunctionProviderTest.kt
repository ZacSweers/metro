// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
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
}
