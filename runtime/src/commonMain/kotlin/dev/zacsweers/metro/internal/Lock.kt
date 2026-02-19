package dev.zacsweers.metro.internal

internal expect class Lock() {
  fun lock()
  fun unlock()
}

internal inline fun <R> locked(lock: Lock, block: () -> R): R {
  lock.lock()
  try {
    return block()
  } finally {
    lock.unlock()
  }
}