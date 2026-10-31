// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A graph with lifecycle callbacks. Metro generates [close], which closes the graph's extensions
 * and then runs its [OnGraphClose] callbacks. Only the first call does anything.
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
