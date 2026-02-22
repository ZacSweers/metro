// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * A meta-annotation that defines a binding container via a separate template class.
 *
 * The [template] class serves as the blueprint: its `@Provides` or `@Binds` functions (each with
 * exactly one type parameter `T` representing the target type) are stamped out for each annotated
 * class, generating delegate functions with the concrete target type substituted for `T`.
 *
 * ## Example — `@Provides` template (object)
 *
 * ```
 * // 1. Define a template object
 * @ContributesTemplate.Template
 * object MyTemplate {
 *   @Provides @IntoSet fun <T : Any> provideIntoSet(target: T): Any = target
 * }
 *
 * // 2. Create a custom annotation referencing the template
 * @ContributesTemplate(template = MyTemplate::class)
 * annotation class CustomAnnotation(val scope: KClass<*>, val replaces: Array<KClass<*>> = [])
 *
 * // 3. Apply it to target classes
 * @CustomAnnotation(AppScope::class)
 * class TargetClass @Inject constructor()
 * ```
 *
 * Metro will generate a top-level binding container object for each annotated class that delegates
 * to the template's `@Provides` functions with the target class substituted for `T`.
 *
 * ## Example — `@Binds` template (abstract class)
 *
 * ```
 * @ContributesTemplate.Template
 * abstract class BindsTemplate {
 *   @Binds abstract fun <T : Base> bind(target: T): Base
 * }
 *
 * @ContributesTemplate(template = BindsTemplate::class)
 * annotation class ContributesBase(val scope: KClass<*>)
 *
 * @ContributesBase(AppScope::class)
 * @Inject
 * class MyImpl : Base
 * ```
 *
 * ## Fixed Scope
 *
 * If all targets should contribute to the same scope, you can specify a [scope] directly on
 * `@ContributesTemplate` to avoid requiring a `scope` parameter on every usage:
 * ```
 * @ContributesTemplate(template = MyTemplate::class, scope = AppScope::class)
 * annotation class CustomAnnotation(val replaces: Array<KClass<*>> = [])
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
 * Template functions can use `@SingleIn(TemplateScope::class)` and the compiler will substitute it
 * with the actual scope at compile time.
 *
 * ## TemplateTarget
 *
 * Template functions can use `TemplateTarget::class` in annotations (e.g.,
 * `@ClassKey(TemplateTarget::class)`) and the compiler will substitute it with the actual target
 * class at compile time.
 *
 * ## Requirements
 * - The annotated annotation must have a `scope: KClass<*>` parameter, or [scope] must be set on
 *   `@ContributesTemplate`. These are mutually exclusive.
 * - The [template] class must be annotated with `@ContributesTemplate.Template`.
 * - The template must be either an `object` (with `@Provides` functions) or `abstract class` (with
 *   `@Binds` functions).
 * - Each template function must have exactly one type parameter (the target type `T`).
 * - Classes annotated with the custom annotation must be injectable (`@Inject` constructor). Kotlin
 *   `object` classes are also supported without `@Inject`.
 *
 * @property template The template class that defines the binding functions.
 * @property scope The fixed scope for all usages of this template annotation. `Nothing::class` (the
 *   default) means no fixed scope is set and the custom annotation must provide its own `scope`
 *   parameter.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class ContributesTemplate(
  val template: KClass<*>,
  val scope: KClass<*> = Nothing::class,
) {
  /** Marks a class as a template for use with [ContributesTemplate]. */
  @Target(AnnotationTarget.CLASS) public annotation class Template

  /** A placeholder scope marker for use in template functions. */
  public abstract class TemplateScope private constructor()

  /** A placeholder target marker for use in template function annotations. */
  public abstract class TemplateTarget private constructor()
}
