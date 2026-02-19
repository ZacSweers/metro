package dev.zacsweers.metro.internal

public expect open class Lock() {
  public fun lock()
  public fun unlock()
}

internal inline fun <R> locked(lock: Lock, block: () -> R): R {
  lock.lock()
  try {
    return block()
  } finally {
    lock.unlock()
  }
}