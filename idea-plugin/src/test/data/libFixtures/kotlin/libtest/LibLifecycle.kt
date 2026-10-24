// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(dev.zacsweers.metro.ExperimentalMetroApi::class)

package libtest

import dev.zacsweers.metro.CloseableGraph
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.OnGraphClose
import dev.zacsweers.metro.OnGraphInit

abstract class LibLifecycleScope

@ContributesTo(LibLifecycleScope::class)
interface LibLifecycleCallbacks : CloseableGraph {
  @OnGraphInit @OnGraphClose fun lifecycle(value: String) {}
}

interface LibInvalidLifecycle : CloseableGraph {
  @OnGraphInit fun initialize()
  @OnGraphClose fun cleanup(): String = "invalid"
}
