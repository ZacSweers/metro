// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_cond_broadcast
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_wait
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

private val threadIdCounter = AtomicLong(0)

@ThreadLocal
private object CurrentThread {
  val id: Long = threadIdCounter.incrementAndFetch()
}

internal actual fun currentThreadId(): Long = CurrentThread.id

// Allocated once for the lifetime of the process, intentionally never destroyed.
private val parkerMutex: CPointer<pthread_mutex_t> = run {
  val mutex = nativeHeap.alloc<pthread_mutex_t>().ptr
  check(pthread_mutex_init(mutex, null) == 0) { "pthread_mutex_init failed" }
  mutex
}

private val parkerCond: CPointer<pthread_cond_t> = run {
  val cond = nativeHeap.alloc<pthread_cond_t>().ptr
  check(pthread_cond_init(cond, null) == 0) { "pthread_cond_init failed" }
  cond
}

internal actual fun parkerLock() {
  pthread_mutex_lock(parkerMutex)
}

internal actual fun parkerUnlock() {
  pthread_mutex_unlock(parkerMutex)
}

internal actual fun parkerBroadcast() {
  pthread_cond_broadcast(parkerCond)
}

internal actual fun parkerWait(lockOwner: Long) {
  pthread_cond_wait(parkerCond, parkerMutex)
}
