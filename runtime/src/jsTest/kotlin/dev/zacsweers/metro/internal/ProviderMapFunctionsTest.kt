// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SuspendProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ProviderMapFunctionsTest {
  @Test
  fun functionMapIsBuiltOnceAndCallsTheProvider() {
    var count = 0
    val providers =
      MapProviderFactory.builder<String, Int>(2)
        .put("b", Provider { 2 })
        .put("a", Provider { ++count })
        .build()
    val functions = providerMapAsFunctions(providers)

    val map = functions()
    assertSame(map, functions())
    assertEquals(listOf("b", "a"), map.keys.toList())
    assertEquals(1, map.getValue("a")())
    assertEquals(2, map.getValue("a")())
  }

  @OptIn(ExperimentalMetroCoroutinesApi::class)
  @Test
  fun suspendFunctionMapIsBuiltOnce() {
    val providers =
      MapSuspendProviderFactory.builder<String, Int>(1).put("a", SuspendProvider { 1 }).build()
    val functions = suspendProviderMapAsFunctions(providers)

    assertSame(functions(), functions())
    assertEquals(listOf("a"), functions().keys.toList())
  }
}
