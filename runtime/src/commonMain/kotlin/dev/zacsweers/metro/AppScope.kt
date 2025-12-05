// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A simple common app-wide _scope key_ that can be used with [SingleIn] or as an aggregation scope.
 *
 * AppScope must be configured by hand within your own dependency graph to be used - Metro does not
 * perform any automatic scope configuration.
 *
 * ## As a scope
 *
 * When used with [SingleIn], it will indicate a scope of the annotated graph and any bindings with
 * matching scopes will have exactly one instance instantiated in that graph's lifecycle.
 *
 * ```kotlin
 * @SingleIn(AppScope::class)
 * @DependencyGraph
 * interface AppGraph {
 *   // ...
 * }
 * ```
 *
 * ## As an aggregation scope
 *
 * When used with [DependencyGraph.scope] and [GraphExtension.scope], it will indicate that the
 * annotated graph aggregates dependencies from other graphs with the same scope key.
 *
 * ```kotlin
 * @DependencyGraph(AppScope::class)
 * interface AppGraph {
 *   // ...
 * }
 * ```
 *
 * Note that Metro treats these graphs as having an implicit [SingleIn] of the same key, so it's
 * redundant to specify both!
 *
 * ```kotlin
 * @DependencyGraph(AppScope::class)
 * @SingleIn(AppScope::class) // <-- Redundant!
 * interface AppGraph {
 *   // ...
 * }
 * ```
 */
public abstract class AppScope private constructor()
