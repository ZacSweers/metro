package dev.zacsweers.lattice.test.integration

import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.annotations.BindsInstance
import dev.zacsweers.lattice.annotations.DependencyGraph
import dev.zacsweers.lattice.annotations.Inject
import dev.zacsweers.lattice.annotations.Singleton
import dev.zacsweers.lattice.createGraphFactory
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.Test

class CycleBreakingTests {
  @Test
  fun `class injections - provider`() {
    val message = "Hello, world!"
    val graph = createGraphFactory<CyclicalGraphBrokenWithProvider.Factory>().create(message)

    val foo = graph.foo
    assertEquals(message, foo.call())

    val barProvider = foo.barProvider
    val barInstance = barProvider()
    assertEquals(message, barInstance.call())
  }

  @DependencyGraph
  interface CyclicalGraphBrokenWithProvider {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@BindsInstance message: String): CyclicalGraphBrokenWithProvider
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
  fun `class injections - lazy`() {
    val message = "Hello, world!"
    val graph = createGraphFactory<CyclicalGraphBrokenWithLazy.Factory>().create(message)

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
  interface CyclicalGraphBrokenWithLazy {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@BindsInstance message: String): CyclicalGraphBrokenWithLazy
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
  fun `class injections - provider of lazy`() {
    val message = "Hello, world!"
    val graph = createGraphFactory<CyclicalGraphBrokenWithProviderOfLazy.Factory>().create(message)

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
  interface CyclicalGraphBrokenWithProviderOfLazy {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@BindsInstance message: String): CyclicalGraphBrokenWithProviderOfLazy
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
  fun `class injections - provider - scoped`() {
    val message = "Hello, world!"
    val graph = createGraphFactory<CyclicalGraphBrokenWithProviderScoped.Factory>().create(message)

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
  interface CyclicalGraphBrokenWithProviderScoped {
    val foo: Foo

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@BindsInstance message: String): CyclicalGraphBrokenWithProviderScoped
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
}