/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.usleep

private val monotonicThreadIdCounter = AtomicInt(1)

@ThreadLocal
private object CurrentThread {
  val id = monotonicThreadIdCounter.incrementAndFetch()
}

// Enabled by default. Set METRO_NATIVE_LOCK_BACKOFF=false/0 in the process environment to restore
// continuous spinning, or true/1 to explicitly enable it. For Apple apps launched from Xcode, set
// this in Scheme > Run > Arguments > Environment Variables.
private val useBackoff =
  getenv("METRO_NATIVE_LOCK_BACKOFF")?.toKString()?.toBooleanFlag(default = true) ?: true

private fun String.toBooleanFlag(default: Boolean): Boolean {
  return when {
    this == "1" || equals("true", ignoreCase = true) -> true
    this == "0" || equals("false", ignoreCase = true) -> false
    else -> default
  }
}

internal actual class Lock {
  @OptIn(ExperimentalNativeApi::class)
  private val spinLock =
    SpinLock(
      currentThreadId = CurrentThread::id,
      useBackoff = useBackoff,
      sleep = ::usleep,
      assert = ::assert,
    )

  actual fun lock() {
    spinLock.lock()
  }

  actual fun unlock() {
    spinLock.unlock()
  }
}
