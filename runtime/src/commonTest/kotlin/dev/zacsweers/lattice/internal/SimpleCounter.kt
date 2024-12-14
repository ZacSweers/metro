package dev.zacsweers.lattice.internal

// Replicates a simple AtomicInt
class SimpleCounter(var count: Int = 0) {
  fun incrementAndGet(): Int = ++count
  fun getAndIncrement(): Int = count++
}
