// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroSuspendApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroSuspendApi
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SuspendProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class SuspendPrimitivesTest {
  @Test
  fun `SyncSuspendProvider delegates to the wrapped provider`() = runTest {
    val provider = Provider { "value" }
    assertEquals("value", SyncSuspendProvider(provider).invoke())
  }

  @Test
  fun `SuspendDelegateFactory resolves through its delegate`() = runTest {
    val factory = SuspendDelegateFactory<String>()
    SuspendDelegateFactory.setDelegate(factory, SuspendProvider { "value" })
    assertEquals("value", factory())
    assertEquals("value", factory.getDelegate()())
  }

  @Test
  fun `SuspendDelegateFactory throws when invoked before delegate is set`() = runTest {
    val factory = SuspendDelegateFactory<String>()
    assertFailsWith<IllegalStateException> { factory() }
  }

  @Test
  fun `SuspendDelegateFactory delegate can only be set once`() = runTest {
    val factory = SuspendDelegateFactory<String>()
    SuspendDelegateFactory.setDelegate(factory, SuspendProvider { "first" })
    assertFailsWith<IllegalStateException> {
      SuspendDelegateFactory.setDelegate(factory, SuspendProvider { "second" })
    }
  }

  @Test
  fun `MapSuspendProviderFactory builds a map of suspend providers`() = runTest {
    val factory =
      MapSuspendProviderFactory.builder<String, Int>(2)
        .put("one", SuspendProvider { 1 })
        .put("two", SuspendProvider { 2 })
        .build()
    val map = factory()
    assertEquals(setOf("one", "two"), map.keys)
    assertEquals(1, map.getValue("one").invoke())
    assertEquals(2, map.getValue("two").invoke())
  }

  @Test
  fun `MapSuspendProviderFactory empty returns an empty map`() = runTest {
    val empty = MapSuspendProviderFactory.empty<String, Int>()
    assertEquals(emptyMap(), empty())
    // empty() is a shared singleton
    assertSame(MapSuspendProviderFactory.empty<String, Int>(), empty)
  }
}
