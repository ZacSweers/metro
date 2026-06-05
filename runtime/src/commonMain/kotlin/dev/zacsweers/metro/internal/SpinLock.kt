/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

private const val SPINS_BEFORE_SLEEP = 64
private const val MAX_SLEEP_MICROS = 1_000u

/**
 * A reentrant spin lock for platforms that do not have a (small enough) runtime lock primitive
 * available. `coroutines-core` has some stuff, but we'd have to pull all that in. Doing true posix
 * locking would also be a pretty big lift.
 *
 * Reentrancy matches the JVM actual's behavior and lets [BaseDoubleCheck] report recursive
 * providers through [reentrantCheck] instead of deadlocking.
 */
internal class SpinLock(
  private val currentThreadId: () -> Int,
  private val useBackoff: Boolean,
  private val sleep: (micros: UInt) -> Unit,
  private val assert: (Boolean) -> Unit,
) {
  // Stores 0 when unlocked, otherwise the non-zero id of the owning thread
  private val locker = AtomicInt(0)
  // Counts additional reentrant acquisitions by the owning thread
  private val reenterCount = AtomicInt(0)

  fun lock() {
    var attempts = 0
    var sleepMicros = 1u
    while (!tryLock()) {
      if (useBackoff && ++attempts >= SPINS_BEFORE_SLEEP) {
        attempts = 0
        sleep(sleepMicros)
        sleepMicros = (sleepMicros * 2u).coerceAtMost(MAX_SLEEP_MICROS)
      }
    }
  }

  fun tryLock(): Boolean {
    val id = currentThreadId()
    val old = locker.compareAndExchange(0, id)
    when (old) {
      id -> {
        // Was locked by us already
        reenterCount.incrementAndFetch()
        return true
      }
      0 -> {
        // We just got the lock
        assert(reenterCount.load() == 0)
        return true
      }
      else -> {
        return false
      }
    }
  }

  fun unlock() {
    val id = currentThreadId()
    check(locker.load() == id) { "thread does not own lock" }
    if (reenterCount.load() > 0) {
      reenterCount.decrementAndFetch()
    } else {
      val old = locker.compareAndExchange(id, 0)
      assert(old == id)
    }
  }
}
