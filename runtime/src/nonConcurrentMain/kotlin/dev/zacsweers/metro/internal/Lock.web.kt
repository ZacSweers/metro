// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

// Web doesn't do multithreading
public actual open class Lock {
  public actual fun lock() {}

  public actual fun unlock() {}
}
