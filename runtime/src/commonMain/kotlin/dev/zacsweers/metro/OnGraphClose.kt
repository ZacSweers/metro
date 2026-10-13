// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Marks a graph function that Metro calls when the graph is closed.
 *
 * ```kotlin
 * @DependencyGraph(AppScope::class)
 * interface AppGraph : CloseableGraph {
 *   @OnGraphClose
 *   fun shutdown(analytics: Analytics) {
 *     analytics.flush()
 *   }
 * }
 * ```
 *
 * The function must have a body and return [Unit]. Metro injects its parameters the same way it
 * resolves graph accessors. Close callbacks run after the graph's tracked graph extensions close.
 *
 * Callbacks can be declared on the graph or on any interface it extends, including [ContributesTo]
 * interfaces. The assembled graph must implement [CloseableGraph] or [SuspendCloseableGraph]. Don't
 * call callbacks directly.
 *
 * A parameter can create its binding for the first time during close. Metro warns when a parameter
 * requests an unscoped binding or one that only close callbacks use.
 *
 * Close callbacks that suspend require [SuspendCloseableGraph].
 */
@ExperimentalMetroApi
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class OnGraphClose
