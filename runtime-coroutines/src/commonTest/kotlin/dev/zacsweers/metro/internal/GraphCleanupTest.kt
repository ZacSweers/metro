// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GraphCleanupTest {
  @Test
  fun cancellingTheCallerLetsCleanupFinish() = runTest {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var finished = false
    val caller = launch {
      runGraphCleanup {
        entered.complete(Unit)
        release.await()
        finished = true
      }
    }
    entered.await()
    caller.cancel()
    release.complete(Unit)
    caller.join()
    assertTrue(finished)
  }
}
