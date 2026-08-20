// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Frontend-independent rules for suspend propagation and request boundaries.
 *
 * Compiler IR and IDEA use different binding models, but a request must stop propagation, pass
 * through a graph dependency, and consume suspend multibindings the same way in both frontends.
 */
public class SuspendBindingRules<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
>(
  private val findBinding: (TypeKey) -> Binding?,
  private val bindingCanPassThrough: (Binding, ContextualTypeKey) -> Boolean,
) {
  /** Whether [binding] can return [request]'s exact wrapper value without unwrapping it. */
  public fun canPassThrough(binding: Binding, request: ContextualTypeKey): Boolean {
    return bindingCanPassThrough(binding, request)
  }

  /** Whether this request prevents a suspend requirement from reaching its consumer. */
  public fun stopsPropagation(request: ContextualTypeKey): Boolean {
    if (request.isDeferrable) return true
    val binding = findBinding(request.typeKey) ?: return false
    return canPassThrough(binding, request)
  }

  /** Whether this dependency makes its consumer require suspend initialization. */
  public fun propagates(
    request: ContextualTypeKey,
    isSuspendKey: (TypeKey) -> Boolean,
  ): Boolean {
    return isSuspendKey(request.typeKey) && !stopsPropagation(request)
  }

  /** Whether a request can safely expose a suspend binding without awaiting it. */
  public fun isValidBoundary(request: ContextualTypeKey): Boolean {
    if (request.isSuspendCapableBoundary) return true
    val binding = findBinding(request.typeKey) ?: return false
    return canPassThrough(binding, request)
  }

  /** Whether this wrapper can consume a multibinding whose elements require suspension. */
  public fun supportsSuspendMultibindingConsumption(
    isSet: Boolean,
    request: ContextualTypeKey,
  ): Boolean {
    return !isSet && request.isMapSuspendProvider
  }
}
