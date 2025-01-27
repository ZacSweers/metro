/*
 * Copyright (C) 2015 The Dagger Authors.
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

package dev.zacsweers.metro.test.integration.cycles

import dev.zacsweers.lattice.Binds
import dev.zacsweers.lattice.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.lattice.IntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.lattice.Provides
import dev.zacsweers.lattice.StringKey
import dev.zacsweers.lattice.createGraph
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Cycle classes used for testing cyclic dependencies.
 *
 * ```
 * A ← (E ← D ← B ← C ← Provider<A>, Lazy<A>), (B ← C ← Provider<A>, Lazy<A>)
 * S ← Provider<S>, Lazy<S>
 * ```
 */
class CyclesTest {

  @Test
  fun providerIndirectionSelfCycle() {
    val selfCycleGraph = createGraph<SelfCycleGraph>()
    val s = selfCycleGraph.s()
    assertNotNull(s.sProvider())
  }

  @Test
  fun providerIndirectionCycle() {
    val cycleGraph = createGraph<CycleGraph>()
    val a = cycleGraph.a()
    val c = cycleGraph.c()
    assertNotNull(c.aProvider())
    assertNotNull(a.b.c.aProvider())
    assertNotNull(a.e.d.b.c.aProvider())
  }

  @Test
  fun lazyIndirectionSelfCycle() {
    val selfCycleGraph = createGraph<SelfCycleGraph>()
    val s = selfCycleGraph.s()
    assertNotNull(s.sLazy.value)
  }

  @Test
  fun lazyIndirectionCycle() {
    val cycleGraph = createGraph<CycleGraph>()
    val a = cycleGraph.a()
    val c = cycleGraph.c()
    assertNotNull(c.aLazy.value)
    assertNotNull(a.b.c.aLazy.value)
    assertNotNull(a.e.d.b.c.aLazy.value)
  }

  //  @Test
  //  fun subcomponentIndirectionCycle() {
  //    val childCycleGraph = CycleGraph.create().child();
  //    val a = childCycleGraph.a();
  //    assertThat(a.b.c.aProvider.get()).isNotNull();
  //    assertThat(a.e.d.b.c.aProvider.get()).isNotNull();
  //  }

  /*
  ## Dagger generates
  class CycleMultibindsGraphImpl implements CycleMultibindsGraph {
    private Provider<X> xProvider = DelegateFactory();
    private Provider mapOfStringAndProviderOfXProvider = MapProviderFactory.<String, X>builder(1).put("X", xProvider).build();
    private Provider<Y> yProvider = DelegateFactory();
    private Provider mapOfStringAndProviderOfYProvider = MapProviderFactory.<String, Y>builder(1).put("Y", yProvider).build();

    private CycleMultibindsGraphImpl() {
      DelegateFactory.setDelegate(yProvider, Y_Factory.create(mapOfStringAndProviderOfXProvider, mapOfStringAndProviderOfYProvider));
      DelegateFactory.setDelegate(xProvider, X_Factory.create(yProvider));
    }

    private Map<String, javax.inject.Provider<X>> mapOfStringAndProviderOfX() {
      return Collections.<String, javax.inject.Provider<X>>singletonMap("X", xProvider);
    }

    private Map<String, javax.inject.Provider<Y>> mapOfStringAndProviderOfY() {
      return Collections.<String, javax.inject.Provider<Y>>singletonMap("Y", yProvider);
    }

    @Override
    public Y y() {
      return new Y(mapOfStringAndProviderOfX(), mapOfStringAndProviderOfY());
    }
  }

  ## Metro generates
  public class ExampleGraphImpl : ExampleGraph {
    private val yProvider: Provider<Y> = DelegateFactory<Y>()
    private val val xProvider: Provider<X> = X_Factory.create(y = <this>.#yProvider)

    public constructor() /* primary */ {
      super/*Any*/()
      /* <init>() */

      DelegateFactory.setDelegate(
        yProvider,
        Y_Factory.create(
          mapOfProvidersOfX = MapProviderFactory.builder<String, X>(size = 1).put(key = "X", providerOfValue = <this>.#xProvider).build(),
          mapOfProvidersOfY = MapProviderFactory.builder<String, Y>(size = 1).put(key = "Y", providerOfValue = <this>.#yProvider).build()
        )
      )
    }

    public override fun x(): X {
      return xProvider.invoke()
    }

    public override fun y(): Y {
      return yProvider.invoke()
    }

    public override val X.x: X
      public override get(): X {
        return error(message = "Never called")
      }

    public override val Y.y: Y
      public override get(): Y {
        return error(message = "Never called")
      }

  }
   */
  // TODO I'm not sure what's functionally different about
  //  what metro generates and what dagger generates, but this infinite loops at runtime
  @Ignore
  @Test
  fun providerMapIndirectionCycle() {
    val cycleMapGraph = createGraph<CycleMapGraph>()
    assertNotNull(cycleMapGraph.y())
    assertContains(cycleMapGraph.y().mapOfProvidersOfX, "X")
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"])
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"]?.invoke())
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"]?.invoke()?.y)
    assertEquals(cycleMapGraph.y().mapOfProvidersOfX.size, 1)
    assertContains(cycleMapGraph.y().mapOfProvidersOfY, "Y")
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfY["Y"])
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfY["Y"]?.invoke())
    assertEquals(cycleMapGraph.y().mapOfProvidersOfY["Y"]!!().mapOfProvidersOfX.size, 1)
    assertEquals(cycleMapGraph.y().mapOfProvidersOfY["Y"]!!().mapOfProvidersOfY.size, 1)
    assertEquals(cycleMapGraph.y().mapOfProvidersOfY.size, 1)
  }

  /**
   * Tests that a cycle where a `@Binds` binding depends on a binding that has to be deferred works.
   */
  @Test
  fun cycleWithDeferredBinds() {
    val bindsCycleGraph = createGraph<BindsCycleGraph>()
    assertNotNull(bindsCycleGraph.bar())
  }

  @Inject class A(val b: B, val e: E)

  @Inject class B(val c: C)

  @Suppress("MEMBERS_INJECT_WARNING")
  @Inject
  class C(val aProvider: Provider<A>) {
    @Inject lateinit var aLazy: Lazy<A>
    @Inject lateinit var aLazyProvider: Provider<Lazy<A>>
  }

  @Inject class D(val b: B)

  @Inject class E(val d: D)

  @Suppress("MEMBERS_INJECT_WARNING")
  @Inject
  class S(val sProvider: Provider<S>) {
    @Inject lateinit var sLazy: Lazy<S>
  }

  @Inject class X(val y: Y)

  @Inject
  class Y(
    val mapOfProvidersOfX: Map<String, Provider<X>>,
    val mapOfProvidersOfY: Map<String, Provider<Y>>,
  )

  @DependencyGraph
  interface CycleMapGraph {
    fun y(): Y

    @Binds @IntoMap @StringKey("X") val X.x: X

    @Binds @IntoMap @StringKey("Y") val Y.y: Y
  }

  @DependencyGraph
  interface CycleGraph {
    fun a(): A

    fun c(): C

    // fun child(): ChildCycleGraph

    @Provides
    private fun provideObjectWithCycle(obj: Provider<Any>): Any {
      return "object"
    }
  }

  @DependencyGraph
  interface SelfCycleGraph {
    fun s(): S
  }

  // TODO revisit after @GraphExtension
  //  @Subcomponent
  //  interface ChildCycleGraph {
  //    A a();
  //
  //    Object object();
  //  }

  interface Foo

  @Inject class Bar(val fooProvider: Provider<Foo>) : Foo

  /**
   * A component with a cycle in which a `@Binds` binding depends on the binding that has to be
   * deferred.
   */
  @DependencyGraph
  interface BindsCycleGraph {
    fun bar(): Bar

    @Binds fun foo(bar: Bar): Foo
  }
}
