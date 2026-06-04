// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import platform.posix.usleep

private val monotonicThreadIdCounter = AtomicInt(1)

@ThreadLocal
private object CurrentThread {
  val id = monotonicThreadIdCounter.incrementAndFetch()
}

private const val SPINS_BEFORE_SLEEP = 64
private const val MAX_SLEEP_MICROS = 1_000u

internal actual class Lock {
  private val locker = AtomicInt(0)
  private val reenterCount = AtomicInt(0)

  actual fun lock() {
    val id = CurrentThread.id
    var attempts = 0
    var sleepMicros = 1u
    while (true) {
      val old = locker.compareAndExchange(0, id)
      when (old) {
        id -> {
          // Was locked by us already
          reenterCount.incrementAndFetch()
          break
        }
        0 -> {
          // We just got the lock
          @OptIn(ExperimentalNativeApi::class) assert(reenterCount.load() == 0)
          break
        }
      }
      if (++attempts >= SPINS_BEFORE_SLEEP) {
        attempts = 0
        usleep(sleepMicros)
        sleepMicros = (sleepMicros * 2u).coerceAtMost(MAX_SLEEP_MICROS)
      }
    }
  }

  actual fun unlock() {
    val id = CurrentThread.id
    check(locker.load() == id) { "thread does not own lock" }
    if (reenterCount.load() > 0) {
      reenterCount.decrementAndFetch()
    } else {
      val old = locker.compareAndExchange(id, 0)
      @OptIn(ExperimentalNativeApi::class) assert(old == id)
    }
  }
}
