// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

fun interface Callable<T> {
  fun call(): T
}
