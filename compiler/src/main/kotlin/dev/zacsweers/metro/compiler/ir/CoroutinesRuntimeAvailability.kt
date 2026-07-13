// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.compiler.symbols.Symbols

internal const val MISSING_RUNTIME_COROUTINES_MESSAGE =
  "Add `dev.zacsweers.metro:runtime-coroutines` to the compile and runtime classpath."

/** Caches optional coroutines-runtime availability for this IR compilation. */
@JvmInline
internal value class CoroutinesRuntimeAvailability(val isAvailable: Boolean) {
  @Inject constructor(symbols: Symbols) : this(symbols.suspendDoubleCheckCompanionObject != null)
}
