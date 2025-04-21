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
 * Instead, `:login` can use `@ContributesGraphExtension(AppScope::class)` to say: "I want to
 * contribute a new graph extension to the app graph." The extension will be generated in
 * `:app`, which already depends on both `:login` and `:user-data`. Now `UserRepository` can be
 * injected in `LoggedInGraph`.
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
 * @DependencyGraph(AppScope::class)
 * interface AppGraph
 * ```
 *
 * The generated code will modify AppGraph to extend `LoggedInGraph.Factory` and implement
 * `createLoggedInGraph()` using a generated final `$$ContributedLoggedInGraph` that includes all
 * contributed bindings, including `UserRepository` from `:user-data`.
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
 * Finally, you can obtain a `LoggedInGraph` instance from `AppGraph` since it now implements `LoggedInGraph.Factory`:
 * ```
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
 *
 * > Note: Abstract factory classes cannot be used as graph contributions.
 */
@Target(CLASS)
public annotation class ContributesGraphExtension(
  /** The scope in which to include this contributed graph interface. */
  val scope: KClass<*>
) {
  /**
   * A factory for the contributed graph extension.
   *
   * Each contributed graph extension must have a factory interface as an inner class. The body of the
   * factory function will be generated when the parent graph is merged.
   *
   * The factory interface must have a single function with the contributed graph extension as its
   * return type. Parameters are supported as mentioned in [ContributesGraphExtension].
   */
  public annotation class Factory(
    /** The scope in which to include this contributed graph interface. */
    val scope: KClass<*>
  )
}