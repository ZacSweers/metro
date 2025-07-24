// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

// Replicates a simple AtomicInt
class SimpleCounter(var count: Int = 0) {
  fun incrementAndGet(): Int = ++count

  fun getAndIncrement(): Int = count++
}
