// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A placeholder scope marker for use in [ContributesTemplate] template interfaces.
 *
 * When a template interface's `@Provides` function uses `@SingleIn(TemplateScope::class)`, the
 * `TemplateScope` reference will be replaced with the actual scope from the custom annotation's
 * `scope` parameter at compile time.
 *
 * ## Example
 *
 * ```kotlin
 * interface ScopedTemplate<T> {
 *   @Provides @SingleIn(TemplateScope::class) fun provideScoped(): Int = 42
 * }
 *
 * @ContributesTemplate(ScopedTemplate::class)
 * annotation class ScopedContainer(val scope: KClass<*>)
 *
 * @ScopedContainer(AppScope::class)
 * @Inject
 * class MyTarget
 * ```
 *
 * In the generated binding container, `@SingleIn(TemplateScope::class)` will be replaced with
 * `@SingleIn(AppScope::class)`.
 */
public abstract class TemplateScope private constructor()
