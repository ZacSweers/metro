// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * Declares the annotated type to be a dependency graph _extension_. As the name implies, graph
 * extensions _extend_ a parent graph they are declared for and contain a superset of bindings that
 * includes both the parent graph(s) as well as their own. These are similar in functionality to
 * Dagger’s Subcomponent
 *
 * _See [DependencyGraph] before reading this section!_
 *
 * Graph extensions must be either an interface or an abstract class.
 *
 * Metro's compiler plugin will build, validate, and implement this graph at compile-time _when the
 * parent graph is generated_.
 *
 * Graph extensions can be chained and implicitly inherit their parents’ scopes.
 *
 * ## Creating Graphs
 *
 * You cannot create a graph extension independent of its parent graph, you may only access it via
 * accessor on the parent graph. You can declare this in multiple ways.
 * * Declare an accessor on the parent graph directly.
 *
 * ```kotlin
 * @GraphExtension
 * interface LoggedInGraph
 *
 * @DependencyGraph
 * interface AppGraph {
 *   val loggedInGraph: LoggedInGraph
 * }
 * ```
 * * (If the extension has a creator) declare the creator on the parent graph directly.
 *
 * ```kotlin
 * @GraphExtension
 * interface LoggedInGraph {
 *   @GraphExtension.Factory
 *   interface Factory {
 *     fun createLoggedInGraph(): LoggedInGraph
 *   }
 * }
 *
 * @DependencyGraph
 * interface AppGraph {
 *   val loggedInGraphFactory: LoggedInGraph.Factory
 * }
 * ```
 * * (If the extension has a creator) make the parent graph implement the creator.
 *
 * ```kotlin
 * @GraphExtension
 * interface LoggedInGraph {
 *   @GraphExtension.Factory
 *   interface Factory {
 *     fun createLoggedInGraph(): LoggedInGraph
 *   }
 * }
 *
 * @DependencyGraph
 * interface AppGraph : LoggedInGraph.Factory
 * ```
 * * Contribute the factory to the parent graph via [ContributesTo] with a scope.
 *
 * ```kotlin
 * @GraphExtension
 * interface LoggedInGraph {
 *   @ContributesTo(AppScope::class)
 *   @GraphExtension.Factory
 *   interface Factory {
 *     fun createLoggedInGraph(): LoggedInGraph
 *   }
 * }
 *
 * @DependencyGraph(AppScope::class)
 * interface AppGraph
 * ```
 *
 * ## Scoping
 *
 * _See [Scope] and [DependencyGraph] before reading this section!_
 *
 * Like [DependencyGraph], graph extensions may declare a [scope] (and optionally [additionalScopes]
 * if there are more). Each of these declared scopes act as an implicit [SingleIn] representation of
 * that scope. For example:
 * ```
 * @GraphExtension(AppScope::class)
 * interface AppGraph
 * ```
 *
 * Is functionally equivalent to writing the below.
 *
 * ```
 * @SingleIn(AppScope::class)
 * @GraphExtension(AppScope::class)
 * interface AppGraph
 * ```
 *
 * ## Providers
 *
 * Like [DependencyGraph], graph extensions may declare providers via [Provides] and [Binds] to
 * provide dependencies into the graph.
 *
 * _Creators_ can provide instance dependencies and other graphs as dependencies.
 *
 * ```
 * @GraphExtension
 * interface AppGraph {
 *   val httpClient: HttpClient
 *
 *   @Provides fun provideHttpClient: HttpClient = HttpClient()
 * }
 * ```
 *
 * ## Creators
 *
 * See [DependencyGraph]'s section on creators.
 *
 * ## Aggregation
 *
 * See [DependencyGraph]'s section on aggregation.
 *
 * ## Contributing Graph Extensions
 *
 * Graph extensions may be _contributed_ to a parent graph and its contribution merging will be
 * deferred until the parent graph is generated.
 *
 * ### The Problem
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
 * ### The Solution
 *
 * Instead, `:login` can use `@GraphExtension(LoggedInScope::class)` + contribute its factory via
 * `@ContributesTo(AppScope::class)` to say: "I want to contribute a new graph extension to the app
 * graph." The extension will be generated in `:app`, which already depends on both `:login` and
 * `:user-data`. Now `UserRepository` can be injected in `LoggedInGraph`.
 *
 * ```
 * @GraphExtension(LoggedInScope::class)
 * interface LoggedInGraph {
 *
 *   val userRepository: UserRepository
 *
 *   @ContributesTo(AppScope::class)
 *   @GraphExtension.Factory
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
 * The generated code will modify `AppGraph` to implement `LoggedInGraph.Factory` and implement
 * `createLoggedInGraph()` using a generated final `LoggedInGraphImpl` class that includes all
 * contributed bindings, including `UserRepository` from `:user-data`.
 *
 * ```
 * // modifications generated during compile-time
 * interface AppGraph : LoggedInGraph.Factory {
 *   override fun createLoggedInGraph(): LoggedInGraph {
 *     return LoggedInGraphImpl(this)
 *   }
 *
 *   // Generated in IR
 *   class $$MetroGraph : AppGraph {
 *     class LoggedInGraphImpl(appGraph: $$MetroGraph) : LoggedInGraph {
 *       // ...
 *     }
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
 * ### Graph arguments
 *
 * You can pass arguments to the graph via the contributed factory:
 * ```
 * @ContributesTo(AppScope::class)
 * @GraphExtension.Factory
 * interface Factory {
 *   fun create(@Provides userId: String): LoggedInGraph
 * }
 * ```
 *
 * This maps to:
 * ```
 * // Generated in IR
 * @DependencyGraph(LoggedInScope::class)
 * class LoggedInGraphImpl(
 *   parent: AppGraph,
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
 *   return LoggedInGraphImpl(this, userId)
 * }
 * ```
 * > Note: Abstract factory classes cannot be used as graph contributions.
 *
 * Like regular graph extensions, contributed graph extensions may also be chained.
 *
 * @property scope The scope this graph extension aggregates.
 * @property additionalScopes Additional scopes this graph extension aggregates. [scope] must be
 *   defined if this is defined, as this property is purely for convenience.
 * @property excludes Optional list of excluded contributing classes (requires a [scope] to be
 *   defined).
 * @property bindingContainers Optional list of included binding containers. See the doc on
 *   [BindingContainer] for more details.
 */
@Target(AnnotationTarget.CLASS)
public annotation class GraphExtension(
  val scope: KClass<*> = Nothing::class,
  val additionalScopes: Array<KClass<*>> = [],
  val excludes: Array<KClass<*>> = [],
  val bindingContainers: Array<KClass<*>> = [],
) {
  /**
   * A factory for the graph extension.
   *
   * Graph extension factories work the same as [DependencyGraph.Factory] except they do not support
   * standalone graph factory generation (since they are generated by the parent graphs.)
   *
   * The factory interface must have a single function with the graph extension as its return type.
   * Parameters are supported as mentioned in [GraphExtension].
   *
   * ## Contributing
   *
   * You can use [ContributesTo] to contribute this factory (if it is an interface) automatically to
   * parent scopes.
   */
  @Target(AnnotationTarget.CLASS) public annotation class Factory
}
