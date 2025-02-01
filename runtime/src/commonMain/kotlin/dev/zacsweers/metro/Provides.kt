// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * This annotation is used on callable declarations in a class to _provide_ instances of a given
 * type to dependency graph. Any class or interface can define providers, but they will not be
 * included unless they are or are a supertype of a [DependencyGraph]-annotated type.
 *
 * ```
 * @DependencyGraph
 * interface AppGraph : NetworkProviders {
 *   @Provides val string: String get() = "Hello, world!"
 * }
 *
 * // Inherited by AppGraph
 * interface NetworkProviders {
 *   @Provides fun provideHttpClient(): HttpClient = HttpClient()
 * }
 * ```
 *
 * Providers are lazily evaluated, at runtime they are always wrapped up in [Provider] (the default)
 * or [Lazy] (if scoped).
 *
 * Provider callables _must_ have explicit return types, it is an error to have an implicit return
 * type.
 *
 * Provider callables may _not_ return nullable types. This may change in the future, see
 * [this discussion](https://github.com/ZacSweers/metro/discussions/153).
 *
 * Provides can declare dependencies as parameters to their functions.
 *
 * ```
 * interface NetworkProviders {
 *   @Provides fun provideHttpClient(cache: Cache): HttpClient = HttpClient(cache)
 * }
 * ```
 *
 * Dependencies may also be wrapped in [Provider] or [Lazy] as needed and the Metro compiler will
 * automatically manage them during injection.
 *
 * ```
 * interface NetworkProviders {
 *   @Provides fun provideHttpClient(lazyCache: Lazy<Cache>): HttpClient = HttpClient(lazyCache)
 * }
 * ```
 *
 * If a provider is _scoped_, it will be managed by the consuming graph as a lazily-evaluated
 * singleton within that scope.
 *
 * ```
 * @SingleIn(AppScope::class)
 * @DependencyGraph
 * interface AppGraph {
 *   @SingleIn(AppScope::class)
 *   @Provides
 *   fun provideHttpClient(): HttpClient = HttpClient()
 * }
 * ```
 *
 * If a provider _qualified_, simply annotate the callable declaration with that qualifier.
 *
 * ```
 * @DependencyGraph
 * interface AppGraph {
 *   @ForScope(AppScope::class)
 *   @Provides
 *   fun provideHttpClient(): HttpClient = HttpClient()
 * }
 * ```
 *
 * Providers may be defined in a `companion object`, allowing for better staticization and slightly
 * more efficient generated code.
 *
 * ```
 * interface NetworkProviders {
 *   companion object {
 *     @Provides
 *     fun provideHttpClient(): HttpClient = HttpClient()
 *   }
 * }
 * ```
 *
 * Providers may be _private_, though note that they will not be visible to downstream compilations
 * in other modules at the moment. Subscribe to
 * [this issue](https://github.com/ZacSweers/metro/issues/53) for updates.
 *
 * ```
 * @DependencyGraph
 * interface AppGraph {
 *   @Provides
 *   private fun provideHttpClient(): HttpClient = HttpClient()
 * }
 * ```
 *
 * To nudge or enforce private providers, you can control their severity via configurable option in
 * the Metro Gradle DSL.
 *
 * ```
 * metro {
 *   publicProviderSeverity.set(NONE|WARN|ERROR)
 * }
 * ```
 *
 * ## DependencyGraph.Factory
 *
 * If a parameter on a [dependency graph factory][DependencyGraph.Factory] is annotated with this,
 * that instance is added as an instance binding to the graph.
 *
 * For example:
 * ```kotlin
 * @DependencyGraph.Factory
 * interface Factory {
 *   fun newMyGraph(
 *     @Provides foo: Foo,
 *     @Provides @Blue bar: Bar
 *   ): MyGraph
 * }
 * ```
 *
 * will allow clients of the factory to pass their own instances of `Foo` and `Bar`, and those
 * instances can be injected within the graph as `Foo` or `@Blue Bar`, respectively.
 */
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Provides
