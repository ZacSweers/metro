// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapFunctionFactoryTest {
  @Test
  fun iterationOrderIsPreserved() {
    val f1: () -> Int = { 1 }
    val f2: () -> Int = { 2 }
    val f3: () -> Int = { 3 }
    val f4: () -> Int = { 4 }

    val factory =
      MapFunctionFactory.builder<String, Int>(4)
        .put("two", f2)
        .put("one", f1)
        .put("three", f3)
        .put("four", f4)
        .build()

    assertEquals(listOf("two", "one", "three", "four"), factory().keys.toList())
    assertEquals(listOf(2, 1, 3, 4), factory().values.map { it() })
  }

  @Test
  fun lastPutWins() {
    val first: () -> Int = { 1 }
    val replacement: () -> Int = { 99 }

    val factory =
      MapFunctionFactory.builder<String, Int>(2).put("a", first).put("a", replacement).build()

    assertSame(replacement, factory()["a"], "Later put should overwrite earlier")
    assertEquals(99, factory().getValue("a")())
  }

  @Test
  fun emptyFactoryAlwaysReturnsSameInstance() {
    val empty1 = MapFunctionFactory.empty<String, Int>()
    val empty2 = MapFunctionFactory.empty<String, Int>()
    assertSame(empty1, empty2, "Empty factories should be the same instance")
    assertTrue(empty1().isEmpty())
  }

  @Test
  fun singletonPreservesLambda() {
    var counter = 0
    val fn: () -> Int = { counter++ }
    val factory = MapFunctionFactory.singleton("only", fn)

    val first = factory()
    assertEquals(setOf("only"), first.keys)
    assertSame(fn, first["only"], "Lambda should be preserved as-is")

    // Lambda is invoked lazily by the consumer, not at map construction
    assertEquals(0, first.getValue("only")())
    assertEquals(1, first.getValue("only")())
  }

  @Test
  fun providerWrappedAsLambdaDispatchesPerCall() {
    // Mirrors what codegen emits on JS: each underlying Metro Provider is wrapped in a
    // Function0 lambda before being put. The stored lambda must dispatch to the Provider on
    // every invocation so unscoped bindings produce a fresh value each call.
    val counter = SimpleCounter()
    val underlying: Provider<Int> = provider { counter.getAndIncrement() }

    val factory = MapFunctionFactory.builder<String, Int>(1).put("counter") { underlying() }.build()

    val fn = factory().getValue("counter")
    assertEquals(0, fn())
    assertEquals(1, fn())
    assertEquals(2, fn())
  }
}
