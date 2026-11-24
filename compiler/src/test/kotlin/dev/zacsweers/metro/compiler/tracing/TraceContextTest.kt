// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import dev.zacsweers.metro.compiler.MetroOptions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraceContextTest {
  @Test
  fun `trace files share a version 7 UUID`() {
    TraceContext(MetroOptions.buildOptions {}).use { context ->
      val id = context.id
      assertEquals(7, UUID.fromString(id).version())
      assertEquals(id, context.id)
    }
  }

  @Test
  fun `disabled tracing lets FIR skip event names`() {
    TraceContext(MetroOptions.buildOptions {}).use { context ->
      assertNull(context.newFirDriverOrNull("main"))
    }
  }
}
