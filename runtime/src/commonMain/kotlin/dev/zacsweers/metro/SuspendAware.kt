// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Opt-in marker for an `@Inject` / `@AssistedInject` class whose construction needs a suspend
 * context — i.e. it has at least one constructor parameter that resolves a suspend binding directly
 * (without wrapping in `SuspendProvider<T>` / `suspend () -> T`).
 *
 * When applied, Metro generates a `SuspendFactory<T>` for the class. The factory's `invoke()` is
 * `suspend` and each constructor parameter is held as a `SuspendProvider<T>` field so suspend deps
 * can be awaited inside the suspend `invoke()` body. Non-suspend deps wrap their existing
 * `Provider<T>` in `CompositeProvider` (allocation-free).
 *
 * **You don't need this annotation when every constructor parameter is already a deferred suspend
 * type** (`SuspendProvider<T>`, `SuspendLazy<T>`, `suspend () -> T`). Those wrap the suspend cost
 * so the class itself doesn't need to await anything during construction.
 *
 * **You do need it** when any constructor parameter is bound to a suspend `@Provides` and isn't
 * wrapped — Metro's IR-level binding validation will report `[Metro/SuspendAwareRequired]` and ask
 * you to either annotate the class or wrap the param.
 *
 * For an `@AssistedInject` target marked `@SuspendAware`, the corresponding `@AssistedFactory` SAM
 * must be declared `suspend`.
 */
@ExperimentalMetroSuspendApi
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
public annotation class SuspendAware
