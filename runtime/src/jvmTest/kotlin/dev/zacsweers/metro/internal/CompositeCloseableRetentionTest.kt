// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompositeCloseableRetentionTest {
  @Test
  fun drainingReleasesRegisteredChildren() {
    val composite = CompositeCloseable()
    val child = registerChild(composite)
    System.gc()
    assertNotNull(child.get())
    composite.close()
    awaitRelease(child)
  }

  @Test
  fun removingReleasesRegisteredChildren() {
    val composite = CompositeCloseable()
    val child = registerChild(composite)
    removeChild(composite, child)
    awaitRelease(child)
  }

  private fun registerChild(composite: CompositeCloseable): WeakReference<Any> {
    val child = Any()
    composite.add(child) {}
    return WeakReference(child)
  }

  private fun removeChild(composite: CompositeCloseable, child: WeakReference<Any>) {
    composite.remove(assertNotNull(child.get()))
  }

  private fun awaitRelease(child: WeakReference<Any>) {
    repeat(100) {
      System.gc()
      if (child.get() == null) {
        return
      }
      Thread.sleep(10)
    }
    assertNull(child.get())
  }
}
