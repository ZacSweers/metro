// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/** The maximum value for a signed 32-bit integer that is equal to a power of 2. */
private const val INT_MAX_POWER_OF_TWO: Int = 1 shl (Int.SIZE_BITS - 2)

public const val REPORT_METRO_MESSAGE: String =
  "This is possibly a bug in the Metro compiler, please report it with details and/or a reproducer to https://github.com/zacsweers/metro."

/**
 * Thread-safety mode used by [memoize]. Defaults to [LazyThreadSafetyMode.PUBLICATION] so callers
 * remain safe under the compiler's parallel transformation pool. The compiler registrar swaps this
 * to [LazyThreadSafetyMode.NONE] when `parallelThreads == 0`, removing the per-access CAS/volatile
 * cost from memoized properties on the hot compile path.
 *
 * Treated as process-global mutable state, matching the existing single-compilation-per-process
 * assumption.
 */
@Volatile
public var memoizeThreadSafetyMode: LazyThreadSafetyMode = LazyThreadSafetyMode.PUBLICATION

public fun <T> memoize(initializer: () -> T): Lazy<T> = lazy(memoizeThreadSafetyMode, initializer)

public fun String.suffixIfNot(suffix: String): String =
  if (this.endsWith(suffix)) this else "$this$suffix"

@Suppress("NOTHING_TO_INLINE")
public inline fun reportCompilerBug(message: String): Nothing {
  error("${message.suffixIfNot(".")} $REPORT_METRO_MESSAGE ")
}

/**
 * Calculate the initial capacity of a map, based on Guava's
 * [com.google.common.collect.Maps.capacity](https://github.com/google/guava/blob/v28.2/guava/src/com/google/common/collect/Maps.java#L325)
 * approach.
 *
 * Pulled from Kotlin stdlib's collection builders. Slightly different from dagger's but
 * functionally the same.
 *
 * @param loadFactor configurable load factor. JVM uses 0.75f, but scatter collections use 7/8.
 */
public fun calculateInitialCapacity(expectedSize: Int, loadFactor: Float = 0.75f): Int =
  when {
    // We are not coercing the value to a valid one and not throwing an exception. It is up to the
    // caller to properly handle negative values.
    expectedSize < 0 -> expectedSize
    expectedSize < 3 -> expectedSize + 1
    expectedSize < INT_MAX_POWER_OF_TWO -> ((expectedSize / loadFactor) + 1.0F).toInt()
    // any large value
    else -> Int.MAX_VALUE
  }
