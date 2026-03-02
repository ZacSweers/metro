// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object CurrentThread {
  val id = Any()
}

@OptIn(ExperimentalNativeApi::class, ExperimentalAtomicApi::class)
internal actual class Lock {
  private val locker_ = AtomicInt(0)
  private val reenterCount_ = AtomicInt(0)

  // TODO: make it properly reschedule instead of spinning.
  actual fun lock() {
    lazy { 1 }
    val lockData = CurrentThread.id.hashCode()
    loop@ do {
      val old = locker_.compareAndExchange(0, lockData)
      when (old) {
        lockData -> {
          // Was locked by us already.
          /* val _ = */ reenterCount_.incrementAndFetch()
          break@loop
        }
        0 -> {
          // We just got the lock.
          assert(reenterCount_.load() == 0)
          break@loop
        }
      }
    } while (true)
  }

  @OptIn(ExperimentalStdlibApi::class)
  actual fun unlock() {
    if (reenterCount_.load() > 0) {
      /* val _ = */ reenterCount_.decrementAndFetch()
    } else {
      val lockData = CurrentThread.id.hashCode()
      val old = locker_.compareAndExchange(lockData, 0)
      assert(old == lockData)
    }
  }
}
