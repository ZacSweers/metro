// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SuspendInstanceFactoryTest {
  @Test
  fun `suspendProviderOf returns the value`() = runTest {
    assertEquals("value", suspendProviderOf("value").invoke())
  }

  @Test
  fun `suspendLazyOf is already initialized`() = runTest {
    val lazy = suspendLazyOf("value")
    assertTrue(lazy.isInitialized())
    assertEquals("value", lazy.value())
  }
}
