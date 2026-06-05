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
    assertTrue(lock.tryLock(), "Initial tryLock should acquire an unlocked lock")

    threadId = 2
    assertFalse(lock.tryLock(), "tryLock should fail while another thread owns the lock")
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
    assertFalse(lock.tryLock(), "tryLock should fail while another thread owns the reentrant lock")

    threadId = 1
    lock.unlock()

    threadId = 2
    assertFalse(lock.tryLock(), "A partial reentrant unlock should not release the lock")

    threadId = 1
    lock.unlock()

    threadId = 2
    assertTrue(lock.tryLock(), "The final reentrant unlock should release the lock")
    lock.unlock()
  }
}
