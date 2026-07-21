// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SuspendInstanceFactoryTest {
  @Test
  fun `suspendProviderOf returns the value`() = runTest {
    assertEquals("value", suspendProviderOf("value").invoke())
  }

  @Test
  fun `suspendProviderOf supports nullable values`() = runTest {
    assertNull(suspendProviderOf<String?>(null).invoke())
  }

  @Test
  fun `suspendLazyOf is already initialized`() = runTest {
    val lazy = suspendLazyOf("value")
    assertTrue(lazy.isInitialized())
    assertEquals("value", lazy.value())
  }

  @Test
  fun `suspendLazyOf supports nullable values`() = runTest {
    val lazy = suspendLazyOf<String?>(null)
    assertTrue(lazy.isInitialized())
    assertNull(lazy.value())
  }
}
