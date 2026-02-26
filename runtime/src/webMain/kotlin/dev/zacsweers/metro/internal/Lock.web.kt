// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

// Web doesn't do multithreading
internal actual class Lock {
  actual fun lock() {}

  actual fun unlock() {}
}
