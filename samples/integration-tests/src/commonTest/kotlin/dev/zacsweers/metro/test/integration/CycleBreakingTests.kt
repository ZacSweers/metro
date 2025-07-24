// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CycleBreakingTests {
  @Test
  fun `class injections cycle can be broken with provider`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithClassesBrokenWithProvider.Factory>().create(message)

    val foo = graph.foo
    assertEquals(message, foo.call())

    val barProvider = foo.barProvider
    val barInstance = barProvider()
    assertEquals(message, barInstance.call())
  }

  @DependencyGraph
  interface CyclicalGraphWithClassesBrokenWithProvider {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides message: String): CyclicalGraphWithClassesBrokenWithProvider
    }

    @Inject
    class Foo(val barProvider: Provider<Bar>) : Callable<String> {
      override fun call() = barProvider().call()
    }

    @Inject
    class Bar(val foo: Foo, val message: String) : Callable<String> {
      override fun call() = message
    }
  }

  @Test
  fun `class injections cycle can be broken with provider - bar exposed first`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithClassesBrokenWithProviderBarExposed.Factory>()
        .create(message)

    val bar = graph.bar
    assertEquals(message, bar.call())

    val foo = bar.foo
    assertEquals(message, foo.call())

    val barProvider = foo.barProvider
    val barInstance = barProvider()
    assertEquals(message, barInstance.call())
  }

  @DependencyGraph
  interface CyclicalGraphWithClassesBrokenWithProviderBarExposed {
    val bar: Bar

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides message: String): CyclicalGraphWithClassesBrokenWithProviderBarExposed
    }

    @Inject
    class Foo(val barProvider: Provider<Bar>) : Callable<String> {
      override fun call() = barProvider().call()
    }

    @Inject
    class Bar(val foo: Foo, val message: String) : Callable<String> {
      override fun call() = message
    }
  }

  @Test
  fun `class injections cycle can be broken with lazy`() {
    val message = "Hello, world!"
    val graph = createGraphFactory<CyclicalGraphWithClassesBrokenWithLazy.Factory>().create(message)

    val foo = graph.foo
    // Multiple calls to the underlying lazy should result in its single instance's count
    // incrementing
    assertEquals(message + "0", foo.call())
    assertEquals(message + "1", foo.call())

    // Assert calling the same on the lazy directly
    val barLazy = foo.barLazy
    val barInstance = barLazy.value
    assertEquals(message + "2", barInstance.call())
    assertEquals(message + "3", barInstance.call())
  }

  @DependencyGraph
  interface CyclicalGraphWithClassesBrokenWithLazy {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides message: String): CyclicalGraphWithClassesBrokenWithLazy
    }

    @Inject
    class Foo(val barLazy: Lazy<Bar>) : Callable<String> {
      override fun call() = barLazy.value.call()
    }

    @Inject
    class Bar(val foo: Foo, val message: String) : Callable<String> {
      private var counter = 0

      override fun call() = message + counter++
    }
  }

  @Test
  fun `class injections cycle can be broken with provider of lazy`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithClassesBrokenWithProviderOfLazy.Factory>().create(message)

    val foo = graph.foo
    // Multiple calls to the underlying provider return new but different lazy instances
    assertEquals(message + "0", foo.call())
    assertEquals(message + "0", foo.call())

    // Assert calling the same on the lazy directly still behave as normal
    val barLazyProvider = foo.barLazyProvider
    val barInstance = barLazyProvider().value
    assertEquals(message + "0", barInstance.call())
    assertEquals(message + "1", barInstance.call())
  }

  @DependencyGraph
  interface CyclicalGraphWithClassesBrokenWithProviderOfLazy {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides message: String): CyclicalGraphWithClassesBrokenWithProviderOfLazy
    }

    @Inject
    class Foo(val barLazyProvider: Provider<Lazy<Bar>>) : Callable<String> {
      override fun call() = barLazyProvider().value.call()
    }

    @Inject
    class Bar(val foo: Foo, val message: String) : Callable<String> {
      private var counter = 0

      override fun call() = message + counter++
    }
  }

  @Test
  fun `scoped class injections cycle can be broken with provider`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithClassesBrokenWithProviderScoped.Factory>().create(message)

    val foo = graph.foo
    assertEquals(message, foo.call())

    val barProvider = foo.barProvider
    val barInstance = barProvider()
    assertEquals(message, barInstance.call())

    // Assert the foo.barProvider.invoke == bar
    assertSame(foo, barInstance.foo)
  }

  @Singleton
  @DependencyGraph
  interface CyclicalGraphWithClassesBrokenWithProviderScoped {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides message: String): CyclicalGraphWithClassesBrokenWithProviderScoped
    }

    @Singleton
    @Inject
    class Foo(val barProvider: Provider<Bar>) : Callable<String> {
      override fun call(): String {
        val bar = barProvider()
        check(bar.foo === this)
        return bar.call()
      }
    }

    @Inject
    class Bar(val foo: Foo, val message: String) : Callable<String> {
      override fun call() = message
    }
  }

  @Test
  fun `provides injections cycle can be broken with provider`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithProvidesBrokenWithProvider.Graph.Factory>()
        .create(message)

    val foo = graph.foo
    assertEquals(message, foo.call())

    val barProvider = foo.barProvider
    val barInstance = barProvider()
    assertEquals(message, barInstance.call())
  }

  // TODO this wrapper is because of a strange bug in kotlinc where TypeResolverService
  //  can't resolve cyclical referencing sibling types
  object CyclicalGraphWithProvidesBrokenWithProvider {
    @DependencyGraph
    interface Graph {
      val foo: Foo

      @DependencyGraph.Factory
      fun interface Factory {
        fun create(@Provides message: String): Graph
      }

      @Provides private fun provideFoo(barProvider: Provider<Bar>): Foo = Foo(barProvider)

      @Provides private fun provideBar(foo: Foo, message: String): Bar = Bar(foo, message)
    }

    class Foo(val barProvider: Provider<Bar>) : Callable<String> {
      override fun call() = barProvider().call()
    }

    class Bar(val foo: Foo, val message: String) : Callable<String> {
      override fun call() = message
    }
  }

  @Test
  fun `provides injections cycle can be broken with lazy`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithProvidesBrokenWithLazy.Graph.Factory>().create(message)

    val foo = graph.foo
    // Multiple calls to the underlying lazy should result in its single instance's count
    // incrementing
    assertEquals(message + "0", foo.call())
    assertEquals(message + "1", foo.call())

    // Assert calling the same on the lazy directly
    val barLazy = foo.barLazy
    val barInstance = barLazy.value
    assertEquals(message + "2", barInstance.call())
    assertEquals(message + "3", barInstance.call())
  }

  // TODO this wrapper is because of a strange bug in kotlinc where TypeResolverService
  //  can't resolve cyclical referencing sibling types
  object CyclicalGraphWithProvidesBrokenWithLazy {
    @DependencyGraph
    interface Graph {
      val foo: Foo

      @DependencyGraph.Factory
      fun interface Factory {
        fun create(@Provides message: String): Graph
      }

      @Provides private fun provideFoo(barLazy: Lazy<Bar>): Foo = Foo(barLazy)

      @Provides private fun provideBar(foo: Foo, message: String): Bar = Bar(foo, message)
    }

    class Foo(val barLazy: Lazy<Bar>) : Callable<String> {
      override fun call() = barLazy.value.call()
    }

    class Bar(val foo: Foo, val message: String) : Callable<String> {
      private var counter = 0

      override fun call() = message + counter++
    }
  }

  @Test
  fun `provides injections cycle can be broken with provider of lazy`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithProvidesBrokenWithProviderOfLazy.Graph.Factory>()
        .create(message)

    val foo = graph.foo
    // Multiple calls to the underlying provider return new but different lazy instances
    assertEquals(message + "0", foo.call())
    assertEquals(message + "0", foo.call())

    // Assert calling the same on the lazy directly still behave as normal
    val barLazyProvider = foo.barLazyProvider
    val barInstance = barLazyProvider().value
    assertEquals(message + "0", barInstance.call())
    assertEquals(message + "1", barInstance.call())
  }

  // TODO this wrapper is because of a strange bug in kotlinc where TypeResolverService
  //  can't resolve cyclical referencing sibling types
  object CyclicalGraphWithProvidesBrokenWithProviderOfLazy {
    @DependencyGraph
    interface Graph {
      val foo: Foo

      @DependencyGraph.Factory
      fun interface Factory {
        fun create(@Provides message: String): Graph
      }

      @Provides
      private fun provideFoo(barLazyProvider: Provider<Lazy<Bar>>): Foo = Foo(barLazyProvider)

      @Provides private fun provideBar(foo: Foo, message: String): Bar = Bar(foo, message)
    }

    class Foo(val barLazyProvider: Provider<Lazy<Bar>>) : Callable<String> {
      override fun call() = barLazyProvider().value.call()
    }

    class Bar(val foo: Foo, val message: String) : Callable<String> {
      private var counter = 0

      override fun call() = message + counter++
    }
  }

  @Test
  fun `scoped provides injections cycle can be broken with provider`() {
    val message = "Hello, world!"
    val graph =
      createGraphFactory<CyclicalGraphWithProvidesBrokenWithProviderScoped.Graph.Factory>()
        .create(message)

    val foo = graph.foo
    assertEquals(message, foo.call())

    val barProvider = foo.barProvider
    val barInstance = barProvider()
    assertEquals(message, barInstance.call())

    // Assert the foo.barProvider.invoke == bar
    assertSame(foo, barInstance.foo)
  }

  // TODO this wrapper is because of a strange bug in kotlinc where TypeResolverService
  //  can't resolve cyclical referencing sibling types
  object CyclicalGraphWithProvidesBrokenWithProviderScoped {
    @Singleton
    @DependencyGraph
    interface Graph {
      val foo: Foo

      @DependencyGraph.Factory
      fun interface Factory {
        fun create(@Provides message: String): Graph
      }

      @Singleton
      @Provides
      private fun provideFoo(barProvider: Provider<Bar>): Foo = Foo(barProvider)

      @Provides private fun provideBar(foo: Foo, message: String): Bar = Bar(foo, message)
    }

    class Foo(val barProvider: Provider<Bar>) : Callable<String> {
      override fun call(): String {
        val bar = barProvider()
        check(bar.foo === this)
        return bar.call()
      }
    }

    class Bar(val foo: Foo, val message: String) : Callable<String> {
      override fun call() = message
    }
  }
}
