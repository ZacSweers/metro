// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SuspendLazyJvmFacadeTest {
  @Test
  fun `suspend lazy helpers from both runtime artifacts are callable`() = runBlocking {
    assertEquals("initialized", suspendLazyOf("initialized").value())
    assertEquals("deferred", suspendLazy { "deferred" }.value())
  }
}
