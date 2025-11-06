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
 * Creates a new _dynamic_ graph of type [T] with the given set of dynamic [containers]. Note this
 * is _only_ applicable for graphs that have no creators (i.e. [DependencyGraph.Factory]).
 *
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   val message: String
 *
 *   @Provides fun provideMessage(): String = "real"
 * }
 *
 * class AppTest {
 *   val testGraph = createGraph<AppGraph>(FakeBindings)
 *
 *   @Test
 *   fun test() {
 *     assertEquals("fake", testGraph.message)
 *   }
 *
 *   @BindingContainer
 *   object FakeBindings {
 *     @Provides fun provideMessage(): String = "fake"
 *   }
 * }
 * ```
 *
 * Dynamic graphs are a powerful feature of the Metro compiler that allow for dynamically replacing
 * bindings in a given graph. The compiler will generate a dynamic graph within the enclosing class
 * or file that is unique to the combination of input [containers] and target type [T].
 *
 * These should be used with care and are generally reserved for tests.
 *
 * **Constraints**
 * - All containers must be instances (or objects) of _binding containers_.
 * - It's an error to pass no containers.
 * - All containers must be non-local, canonical classes. i.e., they must be something with a name!
 * - This overload may be called in a member function body, top-level function body, or property
 *   initializer.
 * - The target [T] graph _must_ be annotated with [@DependencyGraph][DependencyGraph] and must be a
 *   valid graph on its own.
 */
public inline fun <reified T : Any> createDynamicGraph(
  @Suppress("unused") vararg containers: Any
): T {
  throw UnsupportedOperationException("Implemented by the compiler")
}

/**
 * Creates a new instance of a [@DependencyGraph.Factory][DependencyGraph.Factory]-annotated class.
 */
public inline fun <reified T : Any> createGraphFactory(): T {
  throw UnsupportedOperationException("Implemented by the compiler")
}

/**
 * Creates a new _dynamic_ graph factory of type [T] with the given set of dynamic [containers].
 *
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   val message: String
 *
 *   @Provides fun provideMessage(): String = "real"
 * }
 *
 * class AppTest {
 *   val testGraph = createGraphFactory<AppGraph>(FakeBindings)
 *
 *   @Test
 *   fun test() {
 *     assertEquals("fake", testGraph.message)
 *   }
 *
 *   @BindingContainer
 *   object FakeBindings {
 *     @Provides fun provideMessage(): String = "fake"
 *   }
 * }
 * ```
 *
 * Dynamic graphs are a powerful feature of the Metro compiler that allow for dynamically replacing
 * bindings in a given graph. The compiler will generate a dynamic graph within the enclosing class
 * or file that is unique to the combination of input [containers] and target type [T].
 *
 * These should be used with care and are generally reserved for tests.
 *
 * **Constraints**
 * - All containers must be instances (or objects) of _binding containers_.
 * - It's an error to pass no containers.
 * - All containers must be non-local, canonical classes. i.e., they must be something with a name!
 * - This overload may be called in a member function body, top-level function body, or property
 *   initializer.
 * - The target [T] graph _must_ be annotated with [@DependencyGraph][DependencyGraph] and must be a
 *   valid graph on its own.
 */
public inline fun <reified T : Any> createDynamicGraphFactory(
  @Suppress("unused") vararg containers: Any
): T {
  throw UnsupportedOperationException("Implemented by the compiler")
}
