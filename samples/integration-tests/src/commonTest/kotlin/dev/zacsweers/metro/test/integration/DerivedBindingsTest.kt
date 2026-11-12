// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises derived bindings from generic `@Provides` and `@Binds` functions on every target. */
class DerivedBindingsTest {
  @Test
  fun encoderExample() {
    val graph = createGraph<EncoderScenario.AppGraph>()
    val users =
      listOf(
        EncoderScenario.User("Ada", null, mapOf("roles" to listOf("admin", "dev"))),
        EncoderScenario.User("Bob", "bobby", emptyMap()),
      )
    assertEquals(
      "[" +
        "{\"name\":\"Ada\",\"nickname\":null,\"tags\":{\"roles\":[\"admin\",\"dev\"]}}," +
        "{\"name\":\"Bob\",\"nickname\":\"bobby\",\"tags\":{}}" +
        "]",
      graph.exporter.export(users),
    )
  }

  @Test
  fun genericBinds() {
    val graph = createGraph<GenericBindsScenario.AppGraph>()
    assertTrue(graph.stringCache is GenericBindsScenario.LruCache<*>)
    assertTrue(graph.intCache is GenericBindsScenario.LruCache<*>)
    assertEquals("label", graph.stringCache.label)
  }

  @Test
  fun mostSpecificAndExactBindingsWin() {
    val graph = createGraph<SpecificityScenario.AppGraph>()
    assertEquals("exact", graph.strings.name)
    assertEquals("list(int)", graph.ints.name)
    assertEquals("list(anything)", graph.tokens.name)
    assertEquals("anything", graph.token.name)
  }

  @Test
  fun scopedGenericProviderScopesEachKey() {
    val graph = createGraph<ScopedScenario.AppGraph>()
    val strings = graph.stringCache
    assertSame(strings, graph.stringCache)
    val ints = graph.intCache
    assertSame(ints, graph.intCache)
    assertNotSame<Any>(strings, ints)
    assertEquals(2, graph.counter.count)
  }

  @Test
  fun extensionSharesParentScopedGenericProvider() {
    val parent = createGraph<ExtensionScenario.AppGraph>()
    val first = parent.childFactory.create()
    val second = parent.childFactory.create()
    assertSame(first.stringCache, second.stringCache)
    assertSame(first.stringCache, second.stringCacheProvider())
    assertEquals(1, parent.counter.count)
  }

  @Test
  fun reifiedProvider() {
    val graph = createGraph<ReifiedScenario.AppGraph>()
    assertEquals("user:Ada", graph.user.encode(ReifiedScenario.User("Ada")))
    assertEquals("Order:Order(id=1)", graph.order.encode(ReifiedScenario.Order(1)))
    assertEquals("Order:Order(id=2)", graph.orderProvider().encode(ReifiedScenario.Order(2)))
    assertEquals("Order:Order(id=3)", graph.orderFunction().encode(ReifiedScenario.Order(3)))
    assertEquals(
      "[Order:Order(id=1),Order:Order(id=2)]",
      graph.orders.encode(listOf(ReifiedScenario.Order(1), ReifiedScenario.Order(2))),
    )
  }

  fun interface Encoder<T> {
    fun encode(value: T): String
  }

  object EncoderScenario {
    abstract class EncoderScope

    class User(val name: String, val nickname: String?, val tags: Map<String, List<String>>)

    @BindingContainer
    object JsonEncoders {
      @Provides fun string(): Encoder<String> = Encoder { "\"$it\"" }

      @Provides
      fun <T> list(element: Encoder<T>): Encoder<List<T>> = Encoder { values ->
        values.joinToString(",", "[", "]") { element.encode(it) }
      }

      @Provides
      fun <T : Any> nullable(inner: Encoder<T>): Encoder<T?> = Encoder { value ->
        if (value == null) {
          "null"
        } else {
          inner.encode(value)
        }
      }

      @Provides
      fun <V> stringMap(value: Encoder<V>): Encoder<Map<String, V>> = Encoder { map ->
        map.entries.joinToString(",", "{", "}") { "\"${it.key}\":${value.encode(it.value)}" }
      }
    }

    @ContributesBinding(EncoderScope::class)
    @Inject
    class UserEncoder(
      private val string: Encoder<String>,
      private val nickname: Encoder<String?>,
      private val tags: Encoder<Map<String, List<String>>>,
    ) : Encoder<User> {
      override fun encode(value: User): String =
        "{\"name\":${string.encode(value.name)}," +
          "\"nickname\":${nickname.encode(value.nickname)}," +
          "\"tags\":${tags.encode(value.tags)}}"
    }

    @Inject
    class UserExporter(private val encoder: Encoder<List<User>>) {
      fun export(users: List<User>): String = encoder.encode(users)
    }

    @DependencyGraph(EncoderScope::class, bindingContainers = [JsonEncoders::class])
    interface AppGraph {
      val exporter: UserExporter
    }
  }

  object GenericBindsScenario {
    interface Cache<T> {
      val label: String
    }

    @Inject class LruCache<T>(override val label: String) : Cache<T>

    @BindingContainer
    interface CacheBindings {
      @Binds fun <T> lru(impl: LruCache<T>): Cache<T>
    }

    @DependencyGraph(bindingContainers = [CacheBindings::class])
    interface AppGraph {
      val stringCache: Cache<String>
      val intCache: Cache<Int>

      @Provides fun label(): String = "label"
    }
  }

  object SpecificityScenario {
    interface Named<T> {
      val name: String
    }

    class NamedImpl<T>(override val name: String) : Named<T>

    class Token

    @BindingContainer
    object NamedBindings {
      @Provides fun int(): Named<Int> = NamedImpl("int")

      @Provides fun <T> anything(): Named<T> = NamedImpl("anything")

      @Provides fun <T> list(element: Named<T>): Named<List<T>> = NamedImpl("list(${element.name})")

      @Provides fun stringList(): Named<List<String>> = NamedImpl("exact")
    }

    @DependencyGraph(bindingContainers = [NamedBindings::class])
    interface AppGraph {
      val strings: Named<List<String>>
      val ints: Named<List<Int>>
      val tokens: Named<List<Token>>
      val token: Named<Token>
    }
  }

  class Counter {
    var count = 0
  }

  class Cache<T>(val id: Int)

  object ScopedScenario {
    abstract class CacheScope

    @DependencyGraph(CacheScope::class)
    interface AppGraph {
      val counter: Counter
      val stringCache: Cache<String>
      val intCache: Cache<Int>

      @Provides @SingleIn(CacheScope::class) fun counter(): Counter = Counter()

      @Provides
      @SingleIn(CacheScope::class)
      fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
    }
  }

  object ExtensionScenario {
    abstract class ParentScope

    @BindingContainer
    object Caches {
      @Provides
      @SingleIn(ParentScope::class)
      fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
    }

    @DependencyGraph(ParentScope::class, bindingContainers = [Caches::class])
    interface AppGraph {
      val counter: Counter
      val childFactory: ChildGraph.Factory

      @Provides @SingleIn(ParentScope::class) fun counter(): Counter = Counter()
    }

    @GraphExtension
    interface ChildGraph {
      val stringCache: Cache<String>
      val stringCacheProvider: Provider<Cache<String>>

      @GraphExtension.Factory
      interface Factory {
        fun create(): ChildGraph
      }
    }
  }

  object ReifiedScenario {
    abstract class ReifiedScope

    class User(val name: String)

    data class Order(val id: Int)

    @BindingContainer
    object Encoders {
      // Reified providers can't be declared on a graph interface, so this one lives in an object.
      @Provides
      inline fun <reified T> named(): Encoder<T> = Encoder { "${T::class.simpleName}:$it" }

      @Provides
      fun <T> list(element: Encoder<T>): Encoder<List<T>> = Encoder { values ->
        values.joinToString(",", "[", "]") { element.encode(it) }
      }
    }

    @ContributesBinding(ReifiedScope::class)
    @Inject
    class UserEncoder : Encoder<User> {
      override fun encode(value: User): String = "user:${value.name}"
    }

    @DependencyGraph(ReifiedScope::class, bindingContainers = [Encoders::class])
    interface AppGraph {
      val user: Encoder<User>
      val order: Encoder<Order>
      val orderProvider: Provider<Encoder<Order>>
      val orderFunction: () -> Encoder<Order>
      val orders: Encoder<List<Order>>
    }
  }
}
