// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A graph whose generated [close] function runs its cleanup callbacks and closes its children. */
@ExperimentalMetroApi public interface CloseableGraph : AutoCloseable

/** Calls this function after Metro initializes the graph and before the creator returns it. */
@ExperimentalMetroApi
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OnGraphInit

/** Calls this function during graph cleanup. Metro resolves its parameters when cleanup runs. */
@ExperimentalMetroApi
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OnGraphClose
