// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Marks a `@Provides`, `@Binds`, or instance binding declaration in a graph extension as
 * intentionally _overriding_ an inherited binding for the same key from an ancestor graph.
 *
 * By default, an extension that declares a binding for a `(type, qualifier)` already bound in an
 * ancestor is a compile error. Apply this annotation to opt in to overriding the inherited binding
 * for descendants of the declaring graph. The ancestor's view is unaffected.
 *
 * The overriding declaration's scope is what is used by this graph and its descendants — the
 * ancestor's scope is not inherited.
 *
 * For map multibinding contributions (`@Provides @IntoMap @StringKey("k")`),
 * `@OverridesParentBinding` causes this contribution's value to win for that key in the child's
 * merged view, replacing the ancestor's contribution for the same key.
 *
 * `@OverridesParentBinding` is **not** valid on `@IntoSet` or `@ElementsIntoSet` contributions —
 * Set element conflicts cannot be detected at compile time. Applying it to a Set contribution is a
 * compile error.
 *
 * ```
 * @DependencyGraph(AppScope::class)
 * interface AppGraph {
 *   @Provides @SingleIn(AppScope::class) fun provideLogger(): Logger = AppLogger()
 * }
 *
 * @GraphExtension(UserScope::class)
 * interface UserGraph {
 *   @Provides
 *   @SingleIn(UserScope::class)
 *   @OverridesParentBinding
 *   fun provideUserLogger(): Logger = UserLogger()
 * }
 * ```
 *
 * If `@OverridesParentBinding` is present but no ancestor exposes a binding for the same key, it is
 * a compile error — the annotation is redundant and should be removed.
 *
 * This is distinct from the `replaces = [...]` parameter on `@ContributesBinding` and related
 * contribution annotations. `replaces` operates at aggregation/discovery time on contribution
 * classes; `@OverridesParentBinding` operates at resolution time on binding keys across the
 * parent/extension hierarchy.
 */
@ExperimentalMetroApi
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.VALUE_PARAMETER,
)
public annotation class OverridesParentBinding
