// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Creates a new parameter-less graph of type [T]. Note this is _only_ applicable for graphs that
 * have no creators (i.e. [DependencyGraph.Factory]).
 */
public inline fun <reified T : Any> createGraph(): T {
  throw UnsupportedOperationException("Implemented by the compiler")
}

/**
 * TODO doc
 */
public inline fun <reified T : Any> Any.createGraph(vararg containers: Any): T {
  throw UnsupportedOperationException("Implemented by the compiler")
}

// TODO install functions

/**
 * Creates a new instance of a [@DependencyGraph.Factory][DependencyGraph.Factory]-annotated class.
 */
public inline fun <reified T : Any> createGraphFactory(): T {
  throw UnsupportedOperationException("Implemented by the compiler")
}

/**
 * TODO doc
 */
public inline fun <reified T : Any> Any.createGraphFactory(vararg containers: Any): T {
  throw UnsupportedOperationException("Implemented by the compiler")
}
