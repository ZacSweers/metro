// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

fun interface Callable<T> {
  fun call(): T
}
