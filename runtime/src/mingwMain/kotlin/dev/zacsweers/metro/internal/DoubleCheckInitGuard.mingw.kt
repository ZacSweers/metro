// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package dev.zacsweers.metro.internal

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_cond_broadcast
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_tVar
import platform.posix.pthread_cond_wait
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_tVar
import platform.posix.pthread_mutex_unlock

internal actual fun currentThreadId(): Long = syntheticThreadId()

// Shared parker state for contended waiters. This is not the per-instance guard; each guard has its
// own owner field, and provider code does not run while this mutex is held. Allocated once for the
// lifetime of the process and intentionally never destroyed. Note that mingw's winpthreads maps
// pthread_mutex_t/pthread_cond_t to pointer-sized typedefs, hence the *Var types here.
private val parkerMutex: CPointer<pthread_mutex_tVar> = run {
  val mutex = nativeHeap.alloc<pthread_mutex_tVar>().ptr
  check(pthread_mutex_init(mutex, null) == 0) { "pthread_mutex_init failed" }
  mutex
}

private val parkerCond: CPointer<pthread_cond_tVar> = run {
  val cond = nativeHeap.alloc<pthread_cond_tVar>().ptr
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
