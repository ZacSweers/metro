// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/**
 * Generates a graph extension when the parent graph interface is merged.
 *
 * ## The Problem
 *
 * Imagine this module dependency tree:
 * ```
 *         :app
 *       /     \
 *      v       v
 *   :login   :user-data
 * ```
 *
 * `:app` defines the main dependency graph with `@DependencyGraph`. The `:login` module defines a
 * graph extension for authenticated user flows, and `:user-data` provides some core functionality
 * like `UserRepository`.
 *
 * If `:login` defines its own graph directly with `@DependencyGraph`, it won't see contributions
 * from `:user-data` _unless_ `:login` depends on it directly.
 *
 * ## The Solution
 *
 * Instead, `:login` can use `@ContributesGraphExtension(LoggedInScope::class)` to say: "I want to
 * contribute a new graph extension to the app graph." The extension will be generated in `:app`,
 * which already depends on both `:login` and `:user-data`. Now `UserRepository` can be injected in
 * `LoggedInGraph`.
 *
 * ```
 * @ContributesGraphExtension(LoggedInScope::class)
 * interface LoggedInGraph {
 *
 *   val userRepository: UserRepository
 *
 *   @ContributesGraphExtension.Factory(AppScope::class)
 *   interface Factory {
 *     fun createLoggedInGraph(): LoggedInGraph
 *   }
 * }
 * ```
 *
 * In the `:app` module:
 * ```
 * @DependencyGraph(AppScope::class, isExtendable = true)
 * interface AppGraph
 * ```
 *
 * The generated code will modify `AppGraph` to implement `LoggedInGraph.Factory` and implement
 * `createLoggedInGraph()` using a generated final `$$ContributedLoggedInGraph` class that includes
 * all contributed bindings, including `UserRepository` from `:user-data`.
 *
 * ```
 * // modifications generated during compile-time
 * interface AppGraph : LoggedInGraph.Factory {
 *   override fun createLoggedInGraph(): LoggedInGraph {
 *     return $$ContributedLoggedInGraph(this)
 *   }
 *
 *   // Generated in IR
 *   class LoggedInGraph$$MetroGraph(appGraph: AppGraph) : LoggedInGraph {
 *     // ...
 *   }
 * }
 * ```
 *
 * Finally, you can obtain a `LoggedInGraph` instance from `AppGraph` since it now implements
 * `LoggedInGraph.Factory`:
 * ```
 * // Using the asContribution() intrinsic
 * val loggedInGraph = appGraph.asContribution<LoggedInGraph.Factory>().createLoggedInGraph()
 *
 * // Or if you have IDE support enabled
 * val loggedInGraph = appGraph.createLoggedInGraph()
 * ```
 *
 * ## Graph arguments
 *
 * You can pass arguments to the graph via the factory:
 * ```
 * @ContributesGraphExtension.Factory(AppScope::class)
 * interface Factory {
 *   fun create(@Provides userId: String): LoggedInGraph
 * }
 * ```
 *
 * This maps to:
 * ```
 * // Generated in IR
 * @DependencyGraph(LoggedInScope::class)
 * class $$ContributedLoggedInGraph(
 *   @Extends parent: AppGraph,
 *   @Provides userId: String
 * ): LoggedInGraph {
 *   // ...
 * }
 * ```
 *
 * In `AppGraph`, the generated factory method looks like:
 * ```
 * // Generated in IR
 * override fun create(userId: String): LoggedInGraph {
 *   return LoggedInGraph$$MetroGraph(this, userId)
 * }
 * ```
 * > Note: Abstract factory classes cannot be used as graph contributions.
 *
 * Contributed graphs may also be chained, but note that [isExtendable] must be true to do so!
 *
 * @property scope The scope in which to include this contributed graph interface.
 * @property excludes Optional list of excluded contributing classes (requires a [scope] to be
 *   defined).
 * @property isExtendable If enabled, marks this graph as available for extension and generates
 *   extra metadata about this graph's available bindings for child graphs to read.
 * @property bindingContainers Optional list of included binding containers. See the doc on
 *   [BindingContainer] for more details.
 */
@Target(CLASS)
public annotation class ContributesGraphExtension(
  val scope: KClass<*> = Nothing::class,
  val additionalScopes: Array<KClass<*>> = [],
  val excludes: Array<KClass<*>> = [],
  val isExtendable: Boolean = false,
  val bindingContainers: Array<KClass<*>> = [],
) {
  /**
   * A factory for the contributed graph extension.
   *
   * Each contributed graph extension must have a factory interface as an inner class. The body of
   * the factory function will be generated when the parent graph is merged.
   *
   * The factory interface must have a single function with the contributed graph extension as its
   * return type. Parameters are supported as mentioned in [ContributesGraphExtension].
   *
   * @property scope The parent scope in which to include this contributed graph interface. The
   *   graph that this is contributed to _must_ be extendable.
   */
  public annotation class Factory(val scope: KClass<*>)
}
