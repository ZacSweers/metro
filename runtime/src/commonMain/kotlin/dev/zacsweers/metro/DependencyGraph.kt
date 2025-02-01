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
 * ## Creating a graph
 *
 * For simple graphs with no creator types, an implicit one will be generated nad you can instatiate
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
 * ## Creators
 *
 * Graphs can have _creators_. Right now, this just means [Factory] creators. See its doc for more
 * details.
 *
 * ## Aggregation
 *
 * Graphs can automatically _aggregate_ contributed bindings and interfaces by declaring a [scope].
 * If specified, any contributions to the same scope will be automatically aggregated to this graph.
 * This includes contributions generated from [ContributesTo] (supertypes), [ContributesBinding],
 * [ContributesIntoSet], and [ContributesIntoMap].
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
 */
@Target(AnnotationTarget.CLASS)
public annotation class DependencyGraph(
  val scope: KClass<*> = Nothing::class,
  val additionalScopes: Array<KClass<*>> = [],
) {
  /**
   * Graph factories can be declared as a single nested declaration within the target graph to
   * create instances with bound instances (via [Provides]) or graph dependencies.
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
   *      │operator fun invoke()  │       └─┬──────────┬──────┘
   *      │to the companion object│         │          ▼
   *      └───────────────────────┘         │          No
   *                                        │
   *                                        │  ┌───────────────────┐
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
   * If you enable third party FIR plugins in the IDE (TODO link), these will be visible and
   * directly linkable. However, your mileage may vary and it's recommended to stick with the graph
   * creator intrinsics for now until the IDE support is improved.
   */
  @Target(AnnotationTarget.CLASS) public annotation class Factory
}
