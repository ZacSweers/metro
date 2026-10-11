// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Marks a graph function that Metro calls when the graph is created.
 *
 * ```kotlin
 * @DependencyGraph(AppScope::class)
 * interface AppGraph : CloseableGraph {
 *   @OnGraphInit
 *   fun initialize(analytics: Analytics) {
 *     analytics.start()
 *   }
 * }
 * ```
 *
 * The function must have a body and return [Unit]. Metro injects its parameters the same way it
 * resolves graph accessors. Init callbacks run after the graph's ordinary initialization and before
 * the graph is returned. If one throws, creating the graph fails and no close callbacks run.
 *
 * Callbacks can be declared on the graph or on any interface it extends, including [ContributesTo]
 * interfaces. The assembled graph must implement [CloseableGraph] or [SuspendCloseableGraph]. Don't
 * call callbacks directly.
 *
 * A `suspend` callback, or one whose parameters need a suspend context, makes graph creation
 * suspend. The graph then needs a factory whose function is `suspend`.
 */
@ExperimentalMetroApi
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class OnGraphInit
