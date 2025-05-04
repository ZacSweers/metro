// Copyright (C) 2015 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration.cycles

import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Extends
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.createGraphFactory
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

  @Test
  fun graphExtensionIndirectionCycle() {
    val parent = createGraph<CycleGraph>()
    val childCycleGraph = createGraphFactory<ChildCycleGraph.Factory>().create(parent)
    val a = childCycleGraph.a
    assertNotNull(a.b.c.aProvider())
    assertNotNull(a.e.d.b.c.aProvider())
    assertNotNull(childCycleGraph.obj)
  }

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

  @DependencyGraph(isExtendable = true)
  interface CycleGraph {
    fun a(): A

    fun c(): C

    @Provides
    private fun provideObjectWithCycle(obj: Provider<Any>): Any {
      return "object"
    }
  }

  @DependencyGraph
  interface SelfCycleGraph {
    fun s(): S
  }

  @DependencyGraph
  interface ChildCycleGraph {
    val a: A

    val obj: Any

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Extends cycleGraph: CycleGraph): ChildCycleGraph
    }
  }

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
