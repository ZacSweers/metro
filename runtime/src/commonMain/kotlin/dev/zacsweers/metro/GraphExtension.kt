// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * Declares the annotated type to be a dependency graph _extension_. Graph extensions _extend_ a
 * parent [dependency graph][DependencyGraph], containing a superset of dependencies and its own
 * scoping.
 *
 * Graph extensions inherit all the available bindings of the parent graph. For matching
 * multibindings types, they will be merged.
 *
 * Graph extensions can be chained, meaning they can extend other graph extensions.
 *
 * Metro's compiler plugin will build, validate, and implement this graph at compile-time.
 *
 * Graph extensions must be either an interface or an abstract class.
 *
 * Graph extensions are analogous to _Subcomponents_ in Dagger.
 *
 * ## Scoping
 *
 * _See [Scope] before reading this section!_
 *
 * Like [dependency graphs][DependencyGraph], graph extensions may also declare a [scope] (and
 * optionally [additionalScopes] if there are more). Each of these declared scopes act as an
 * implicit [SingleIn] representation of that scope. For example:
 * ```
 * @GraphExtension(LoggedInScope::class)
 * interface LoggedInGraph
 * ```
 *
 * Is functionally equivalent to writing the below.
 *
 * ```
 * @SingleIn(LoggedInScope::class)
 * @GraphExtension(LoggedInScope::class)
 * interface LoggedInGraph
 * ```
 *
 * ## Creating a graph extension
 *
 * Unlike [dependency graphs][DependencyGraph], graph extensions can only be created _from_ their
 * parent graphs.
 *
 * For simple extensions with no creator types, you can just expose the extension type on the parent
 * graph type as an accessor.
 *
 * ```
 * @GraphExtension
 * interface LoggedInGraph
 *
 * @DependencyGraph
 * interface AppGraph {
 *   val loggedInGraph: LoggedInGraph
 * }
 *
 * val loggedInGraph = createGraph<AppGraph>().loggedInGraph
 * ```
 *
 * For creators (more below), you can expose the creator type on the parent graph type as an
 * accessor.
 *
 * ```
 * @GraphExtension
 * interface LoggedInGraph {
 *   @GraphExtension.Factory
 *   fun interface Factory {
 *     fun create(token: Token): LoggedInGraph
 *   }
 * }
 *
 * @DependencyGraph
 * interface AppGraph {
 *   val loggedInGraphFactory: LoggedInGraph.Factory
 * }
 *
 * val loggedInGraph = createGraph<AppGraph().loggedInGraphFactory.create(Token(...))
 * ```
 *
 * ## Providers
 *
 * Like [DependencyGraph], graph extensions can declare providers in the same way.
 *
 * ## Creators
 *
 * Graphs can have _creators_. Right now, this just means [Factory] creators. See its doc for more
 * details.
 *
 * ## Aggregation
 *
 * Like [DependencyGraph], graph extensions can automatically _aggregate_ contributed bindings and
 * interfaces. Any contributions to the same scope will be automatically aggregated to this graph.
 * This includes contributions generated from [ContributesTo] (supertypes), [ContributesBinding],
 * [ContributesIntoSet], and [ContributesIntoMap].
 */
@Target(AnnotationTarget.CLASS)
public annotation class GraphExtension(
  val scope: KClass<*> = Nothing::class,
  val additionalScopes: Array<KClass<*>> = [],
) {
  /**
   * Graph extension factories can be declared as a single nested declaration within the target
   * graph to create instances with bound instances (via [Provides]) or graph dependencies.
   *
   * ```
   * @GraphExtension
   * interface LoggedInGraph {
   *   @GraphExtension.Factory
   *   fun interface Factory {
   *     fun create(@Provides token: Token, networkGraph: NetworkGraph)
   *   }
   * }
   * ```
   *
   * In the above example, the `token` parameter is an _instance_ binding (analogous to
   * `@BindsInstance` in Dagger) and available as a binding on the graph.
   *
   * The `networkGraph` parameter is a _graph_ dependency. This can be any type and is treated as
   * another [GraphExtension] type. Any type these deps expose as _accessors_ are available as
   * bindings to this graph. For example:
   * ```
   * interface NetworkGraph {
   *   val httpClient: HttpClient
   * }
   * ```
   *
   * In this case, `HttpClient` would be an available binding in the consuming `AppGraph`. Only
   * explicitly declared accessors are considered candidates for bindings.
   */
  @Target(AnnotationTarget.CLASS) public annotation class Factory
}
