// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * A meta-annotation that defines a binding container via the annotation class's companion object.
 *
 * The companion object serves as the template: its `@Provides` functions (each with exactly one
 * type parameter `T` representing the target type) are stamped out for each annotated class,
 * generating delegate functions that call back to the companion with the concrete target type.
 *
 * ## Example
 *
 * ```
 * // 1. Create a custom annotation with a companion object containing @Provides functions
 * @ContributesTemplate
 * annotation class CustomAnnotation(val scope: KClass<*>, val replaces: Array<KClass<*>> = []) {
 *   companion object {
 *     @Provides @IntoSet fun <T : Any> provideIntoSet(target: T): Any = target
 *   }
 * }
 *
 * // 2. Apply it to target classes
 * @CustomAnnotation(AppScope::class)
 * class TargetClass @Inject constructor()
 * ```
 *
 * Metro will generate a nested binding container object for each annotated class that delegates to
 * the companion's `@Provides` functions with the target class substituted for `T`.
 *
 * ## Fixed Scope
 *
 * If all targets should contribute to the same scope, you can specify a [scope] directly on
 * `@ContributesTemplate` to avoid requiring a `scope` parameter on every usage:
 * ```
 * @ContributesTemplate(scope = AppScope::class)
 * annotation class CustomAnnotation(val replaces: Array<KClass<*>> = []) {
 *   companion object {
 *     @Provides fun <T> provideInt(): Int = 42
 *   }
 * }
 *
 * @CustomAnnotation  // no scope needed!
 * class TargetClass @Inject constructor()
 * ```
 *
 * Scope declaration is mutually exclusive: either `@ContributesTemplate` specifies a fixed [scope],
 * or the custom annotation must declare its own `scope` parameter for per-usage scope. Both cannot
 * be used simultaneously.
 *
 * ## TemplateScope
 *
 * Companion `@Provides` functions can use `@SingleIn(TemplateScope::class)` and the compiler will
 * substitute it with the actual scope at compile time.
 *
 * ## Requirements
 * - The annotated annotation must have a `scope: KClass<*>` parameter, or [scope] must be set on
 *   `@ContributesTemplate`. These are mutually exclusive.
 * - The annotation must have a companion object with at least one `@Provides` function.
 * - Each companion `@Provides` function must have exactly one type parameter (the target type `T`).
 * - Classes annotated with the custom annotation must be injectable (`@Inject` constructor). Kotlin
 *   `object` classes are also supported without `@Inject`.
 *
 * @property scope The fixed scope for all usages of this template annotation. `Nothing::class` (the
 *   default) means no fixed scope is set and the custom annotation must provide its own `scope`
 *   parameter.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class ContributesTemplate(val scope: KClass<*> = Nothing::class)
