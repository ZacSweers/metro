// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Tries registered bindings before collection synthesis, optional declarations, derived bindings,
 * and class lookup. A null result continues lookup. Any non-null result is final, including an
 * empty collection. Callers own their caches, request checks, and binding creation.
 *
 * Derived bindings come from generic providers whose return type matches the key. They run after
 * optional declarations and before class lookup.
 */
public inline fun <T : Any> selectBinding(
  registered: () -> T?,
  multibinding: () -> T?,
  optional: () -> T?,
  derived: () -> T?,
  implicit: () -> T?,
): T? {
  val registeredBinding = registered()
  if (registeredBinding != null) return registeredBinding
  val collectionBinding = multibinding()
  if (collectionBinding != null) return collectionBinding
  val optionalBinding = optional()
  if (optionalBinding != null) return optionalBinding
  val derivedBinding = derived()
  if (derivedBinding != null) return derivedBinding
  return implicit()
}
