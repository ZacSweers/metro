// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A graph with lifecycle callbacks. Metro generates [close], which closes the graph's extensions
 * and then runs its [OnGraphClose] callbacks. Only the first call does anything.
 *
 * An extension or callback that fails doesn't stop the others. [close] throws the first failure
 * after they've all run, with later failures attached as suppressed exceptions.
 *
 * [close] waits for an extension that's already closing on another thread. A
 * `SuspendCloseableGraph` parent doesn't wait. A close callback that closes an ancestor graph can
 * hang if another thread is closing a graph between the two at the same time. A close callback that
 * blocks on another thread while that thread closes an ancestor of this graph also hangs.
 */
@ExperimentalMetroApi public interface CloseableGraph : AutoCloseable

/**
 * Metro calls the annotated graph function after it creates the graph and before the creator
 * returns it. Metro injects the function's parameters.
 */
@ExperimentalMetroApi
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OnGraphInit

/**
 * Metro calls the annotated graph function when the graph closes. Metro injects the function's
 * parameters when it calls the function.
 */
@ExperimentalMetroApi
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OnGraphClose
