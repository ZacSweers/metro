// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.usleep

private val monotonicThreadIdCounter = AtomicInt(1)

// Enabled by default. Set METRO_NATIVE_LOCK_BACKOFF=false/0 in the process environment to restore
// continuous spinning, or true/1 to explicitly enable it. For Apple apps launched from Xcode, set
// this in Scheme > Run > Arguments > Environment Variables.
private val useBackoff =
  getenv("METRO_NATIVE_LOCK_BACKOFF")?.toKString()?.toBooleanFlag(default = true) ?: true

@ThreadLocal
private object CurrentThread {
  val id = monotonicThreadIdCounter.incrementAndFetch()
}

private const val SPINS_BEFORE_SLEEP = 64
private const val MAX_SLEEP_MICROS = 1_000u

private fun String.toBooleanFlag(default: Boolean): Boolean {
  return when {
    this == "1" || equals("true", ignoreCase = true) -> true
    this == "0" || equals("false", ignoreCase = true) -> false
    else -> default
  }
}

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
      if (useBackoff && ++attempts >= SPINS_BEFORE_SLEEP) {
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
