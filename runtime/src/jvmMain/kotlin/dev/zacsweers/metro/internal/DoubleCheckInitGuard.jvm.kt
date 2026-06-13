// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

public actual typealias DoubleCheckInitGuard = Any

/**
 * Synchronizes on the instance's own monitor, exactly like Dagger's `DoubleCheck`. Monitors are
 * reentrant, allocation-free, and park properly under contention.
 *
 * Virtual threads (Loom) note: on JDK 21-23, `synchronized` pins a virtual thread to its carrier if
 * the thread blocks while holding (or acquiring) the monitor ([JEP
 * 444](https://openjdk.org/jeps/444) recommended `ReentrantLock` for such regions). JDK 24+ removes
 * monitor pinning entirely ([JEP 491](https://openjdk.org/jeps/491)). We accept the JDK 21-23
 * trade-off because this monitor is only ever contended during a binding's one-time initialization
 * (typically startup and/or in-memory construction), and pinning affects scalability, not
 * correctness. If a scoped provider does blocking I/O during first init on a JDK 21-23
 * virtual-thread-heavy server, that one initialization may briefly pin a carrier.
 */
@OptIn(ExperimentalContracts::class)
internal actual inline fun <T> DoubleCheckInitGuard.guarded(block: () -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  return synchronized(this, block)
}
