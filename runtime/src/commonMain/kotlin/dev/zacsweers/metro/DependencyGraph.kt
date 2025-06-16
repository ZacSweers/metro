// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * Declares the annotated type to be a dependency graph. Metro's compiler plugin will build,
 * validate, and implement this graph at compile-time.
 *
 * Graph types must be either an interface or an abstract class.
 *
 * ## Scoping
 *
 * _See [Scope] before reading this section!_
 *
 * Graphs may declare a [scope] (and optionally [additionalScopes] if there are more). Each of these
 * declared scopes act as an implicit [SingleIn] representation of that scope. For example:
 * ```
 * @DependencyGraph(AppScope::class)
 * interface AppGraph
 * ```
 *
 * Is functionally equivalent to writing the below.
 *
 * ```
 * @SingleIn(AppScope::class)
 * @DependencyGraph(AppScope::class)
 * interface AppGraph
 * ```
 *
 * ## Creating a graph
 *
 * For simple graphs with no creator types, an implicit one will be generated. You can instantiate
 * them with [createGraph].
 *
 * ```
 * val graph = createGraph<AppGraph>()
 * ```
 *
 * For creators (more below), you can create a factory with [createGraphFactory].
 *
 * ```
 * val graph = createGraphFactory<AppGraph.Factory().create("hello!")
 * ```
 *
 * ## Providers
 *
 * Graph types can declare providers via [Provides] and [Binds] to provide dependencies into the
 * graph.
 *
 * Graph _creators_ can provide instance dependencies and other graphs as dependencies.
 *
 * ```
 * @DependencyGraph
 * interface AppGraph {
 *   val httpClient: HttpClient
 *
 *   @Provides fun provideHttpClient: HttpClient = HttpClient()
 * }
 * ```
 *
 * ## Creators
 *
 * Graphs can have _creators_. Right now, this just means [Factory] creators. See its doc for more
 * details.
 *
 * ## Aggregation
 *
 * Graphs can automatically _aggregate_ contributed bindings and interfaces. Any contributions to
 * the same scope will be automatically aggregated to this graph. This includes contributions
 * generated from [ContributesTo] (supertypes), [ContributesBinding], [ContributesIntoSet], and
 * [ContributesIntoMap].
 *
 * ```
 * @DependencyGraph(AppScope::class)
 * interface AppGraph
 *
 * @ContributesTo(AppScope::class)
 * interface HttpClientProvider {
 *   val httpClient: HttpClient
 * }
 *
 * @ContributesBinding(AppScope::class)
 * @Inject
 * class RealHttpClient(...) : HttpClient
 *
 * // Results in generated code like...
 * @DependencyGraph(AppScope::class)
 * interface AppGraph : HttpClientProvider {
 *   /* fake */ override val RealHttpClient.bind: HttpClient
 * }
 * ```
 *
 * ## Graph Extensions
 *
 * Dependency graphs can be marked as extendable to allow child graphs to extend them. These are
 * similar in functionality to Dagger’s Subcomponents but are detached in nature like in
 * kotlin-inject.
 *
 * A graph must opt itself into extension in via marking [isExtendable] to true, which will make the
 * Metro compiler generate extra metadata for downstream child graphs.
 *
 * Then, a child graph can add an [@Extends][Extends]-annotated parameter to its creator to extend
 * that graph.
 *
 * ```kotlin
 * @DependencyGraph(isExtendable = true)
 * interface AppGraph {
 *   @Provides fun provideHttpClient(): HttpClient { ... }
 * }
 *
 * @DependencyGraph
 * interface UserGraph {
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     fun create(@Extends appGraph: AppGraph): UserGraph
 *   }
 * }
 * ```
 *
 * Child graphs then contain a superset of bindings they can inject, including both their bindings
 * and their parents'. Graph extensions can be chained as well.
 *
 * Child graphs also implicitly inherit their parents’ scopes.
 *
 * ### Hoisting unused scoped class injections in parent graphs
 *
 * In some cases, there are scoped bindings that are unused in the parent graph but _are_ used in
 * child graphs. Due to the detached nature of graph extensions, these bindings by default end up
 * scoped to the child. If you want to enforce that these bindings are scoped and held by the parent
 * however, you can enable `enableScopedInjectClassHints()` in the Gradle DSL to enable automatic
 * scoped inject class discovery by parent graphs.
 *
 * Note that this must be enabled in the compilation of the scoped class itself.
 *
 * @property excludes Optional list of excluded contributing classes (requires a [scope] to be
 *   defined).
 * @property isExtendable If enabled, marks this graph as available for extension and generates
 *   extra metadata about this graph's available bindings for child graphs to read.
 */
@Target(AnnotationTarget.CLASS)
public annotation class DependencyGraph(
  val scope: KClass<*> = Nothing::class,
  val additionalScopes: Array<KClass<*>> = [],
  val excludes: Array<KClass<*>> = [],
  val isExtendable: Boolean = false,
) {
  /**
   * Graph factories can be declared as a single nested declaration within the target graph to
   * create instances with bound instances (via [Provides]), graph dependencies (via [Includes]), or
   * parent graphs to extend (via [Extends]).
   *
   * ```
   * @DependencyGraph
   * interface AppGraph {
   *   @DependencyGraph.Factory
   *   fun interface Factory {
   *     fun create(@Provides text: String, networkGraph: NetworkGraph)
   *   }
   * }
   * ```
   *
   * In the above example, the `text` parameter is an _instance_ binding (analogous to
   * `@BindsInstance` in Dagger) and available as a binding on the graph.
   *
   * The `networkGraph` parameter is a _graph_ dependency. This can be any type and is treated as
   * another [DependencyGraph] type. Any type these deps expose as _accessors_ are available as
   * bindings to this graph. For example:
   * ```
   * interface NetworkGraph {
   *   val httpClient: HttpClient
   * }
   * ```
   *
   * In this case, `HttpClient` would be an available binding in the consuming `AppGraph`. Only
   * explicitly declared accessors are considered candidates for bindings.
   *
   * ## Graph factory generation
   *
   * Metro will automatically generate one of the following scenarios for graph factories:
   * ```
   *                      ┌────────────────────┐
   *                ┌─────┤Has a graph factory?├──┐
   *                │     └────────────────────┘  │
   *                ▼                             ▼
   *                No                           Yes
   *                │                             │
   *                │                             │
   *      ┌─────────▼─────────────┐       ┌───────▼───────────┐
   *      │  Generate an empty    │       │Is it an interface?│
   *      │ operator fun invoke() │       └─┬──────────┬──────┘
   *      │to the companion object│         │          ▼
   *      └───────────────────────┘         │          No
   *                                        │          │
   *                                        │  ┌───────▼───────────┐
   *                    Yes◄────────────────┘  │ Is there already  │
   *                     │                     │a companion object?│
   *            ┌────────▼──────────┐          └┬────────────┬─────┘
   *            │ Is there already  │           │            │
   *            │a companion object?│           ▼            ▼
   *            └┬─────────────────┬┘          Yes           No───┐
   *             ▼                 ▼            │                 │
   *            Yes                No           │         ┌───────▼────┐
   *             │                 │            ├─────────┤Generate one│
   * ┌───────────▼─────────┐       │            │         └────────────┘
   * │Generate a matching  │       │          ┌─▼───────────────────────────┐
   * │operator fun invoke()│       │          │Generate a factory() function│
   * │function into it     │       │          │    into it                  │
   * └─────────────────────┘       │          └─────────────────────────────┘
   *                               │
   *                               │
   *              ┌────────────────▼───────┐
   *              │Generate one and make it│
   *              │implement the factory   │
   *              │interface               │
   *              └────────────────────────┘
   * ```
   *
   * All of this happens under the hood and [createGraph]/[createGraphFactory] with resolve the
   * correct one to use.
   *
   * ## Using generated declarations directly
   *
   * If you
   * [enable third party FIR plugins in the IDE](https://zacsweers.github.io/metro/installation/#ide-support),
   * these will be visible and directly linkable. However, your mileage may vary and it's
   * recommended to stick with the graph creator intrinsics for now until the IDE support is
   * improved.
   */
  @Target(AnnotationTarget.CLASS) public annotation class Factory
}
