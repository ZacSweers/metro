// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

// Web doesn't do multithreading
public actual open class DoubleCheckInitGuard actual constructor()

@OptIn(ExperimentalContracts::class)
internal actual inline fun <T> DoubleCheckInitGuard.guarded(block: () -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  return block()
}
