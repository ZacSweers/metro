// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import dev.zacsweers.metro.compiler.MetroOptions
import kotlin.test.Test
import kotlin.test.assertNull

class TraceContextTest {
  @Test
  fun `disabled tracing lets FIR skip event names`() {
    TraceContext(MetroOptions.buildOptions {}).use { context ->
      assertNull(context.newFirDriverOrNull("main"))
    }
  }
}
