/*
 * Copyright 2025 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package dev.zacsweers.metro.internal

import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import platform.posix.pthread_cond_broadcast
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_wait
import platform.posix.pthread_get_qos_class_np
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_override_qos_class_end_np
import platform.posix.pthread_override_qos_class_start_np
import platform.posix.pthread_override_t
import platform.posix.pthread_self
import platform.posix.qos_class_self

// The owner word doubles as the owning thread's pthread_t for QoS donation in parkerWait().
internal actual fun currentThreadId(): Long = pthread_self().toLong()

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
  donateQos(lockOwner)
  pthread_cond_wait(parkerCond, parkerMutex)
  clearQosDonation()
}

// QoS override held by the current (waiting) thread, if any. Thread-confined.
@ThreadLocal private var qosOverride: pthread_override_t? = null
@ThreadLocal private var qosOverrideQosClass: UInt = 0u

/**
 * pthread condvars don't do priority inheritance, so a high-QoS waiter parked behind a low-QoS
 * initializer would otherwise be priority-inverted. Donate the waiter's QoS class to the owner for
 * the duration of the wait. Ported from kotlinx-atomicfu's Apple `NativeMutexNode`.
 */
private fun donateQos(lockOwner: Long) {
  if (lockOwner == 0L) return
  val ourQosClass = qos_class_self()
  if (qosOverride != null) {
    // There is an existing override, but we need to go higher.
    if (ourQosClass > qosOverrideQosClass) {
      pthread_override_qos_class_end_np(qosOverride)
      qosOverride = pthread_override_qos_class_start_np(lockOwner.toCPointer(), ourQosClass, 0)
      qosOverrideQosClass = ourQosClass
    }
  } else {
    // No existing override, check if we need to set one up.
    memScoped {
      val lockOwnerQosClass = alloc<UIntVar>()
      val lockOwnerRelPrio = alloc<IntVar>()
      pthread_get_qos_class_np(lockOwner.toCPointer(), lockOwnerQosClass.ptr, lockOwnerRelPrio.ptr)
      if (ourQosClass > lockOwnerQosClass.value) {
        qosOverride = pthread_override_qos_class_start_np(lockOwner.toCPointer(), ourQosClass, 0)
        qosOverrideQosClass = ourQosClass
      }
    }
  }
}

private fun clearQosDonation() {
  if (qosOverride != null) {
    pthread_override_qos_class_end_np(qosOverride)
    qosOverride = null
  }
}
