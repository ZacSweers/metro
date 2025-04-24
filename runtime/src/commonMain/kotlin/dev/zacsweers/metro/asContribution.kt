// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Casts [this] graph instance as the expected contribution type [T]. This is useful for cases where
 * you want to upcast a merged graph to an expected contribution type.
 *
 * This is validated at compile-time to prevent unexpected casts.
 *
 * This is not necessary if you enable IDE support.
 *
 * @see <a href="https://zacsweers.github.io/metro/installation/#ide-support">Docs for how to enable
 *   IDE support</a>
 */
@Suppress("UnusedReceiverParameter")
public inline fun <reified T : Any> Any.asContribution(): T {
  throw NotImplementedError("Implemented by the compiler")
}
