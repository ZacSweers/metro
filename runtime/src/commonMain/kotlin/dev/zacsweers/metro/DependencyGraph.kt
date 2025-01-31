/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 *
 * ## Generating graph creators
 *
 * TODO
 */
@Target(AnnotationTarget.CLASS)
public annotation class DependencyGraph(
  val scope: KClass<*> = Nothing::class,
  val additionalScopes: Array<KClass<*>> = [],
) {
  @Target(AnnotationTarget.CLASS) public annotation class Factory
}
