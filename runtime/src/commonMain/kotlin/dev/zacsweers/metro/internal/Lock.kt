package dev.zacsweers.metro.internal

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal expect class Lock() {
  fun lock()

  fun unlock()
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T> Lock.withLock(action: () -> T): T {
  contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
  lock()
  try {
    return action()
  } finally {
    unlock()
  }
}
