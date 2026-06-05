// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpinLockConditionTest {
  private var threadId = 1
  private val lock =
    SpinLock(
      currentThreadId = { threadId },
      useBackoff = false,
      sleep = {},
      assert = ::check,
    )

  @Test
  fun tryLockReturnsFalseWhenOwnedByAnotherThread() {
    assertTrue(lock.tryLock())

    threadId = 2
    assertFalse(lock.tryLock())
  }

  @Test
  fun unlockFromAnotherThreadFails() {
    lock.lock()

    threadId = 2
    assertFailsWith<IllegalStateException> { lock.unlock() }

    threadId = 1
    lock.unlock()
  }

  @Test
  fun reentrantUnlockOnlyReleasesAfterFinalUnlock() {
    lock.lock()
    lock.lock()

    threadId = 2
    assertFalse(lock.tryLock())

    threadId = 1
    lock.unlock()

    threadId = 2
    assertFalse(lock.tryLock())

    threadId = 1
    lock.unlock()

    threadId = 2
    assertTrue(lock.tryLock())
    lock.unlock()
  }

  private fun spinLock(): SpinLock {
    return SpinLock(
      currentThreadId = { threadId },
      useBackoff = false,
      sleep = {},
      assert = ::check,
    )
  }
}
