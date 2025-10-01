// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Assisted injection is a type of injection where some dependencies may be fulfilled by the host
 * dependency graph but some others may be _assisted_ at runtime during instantiation. This is
 * useful for deferring some inputs to dynamic values supplied at runtime.
 *
 * For example, an `HttpClient` may accept a user-preferenced timeout duration.
 *
 * ```
 * @AssistedInject
 * class HttpClient(
 *   @Assisted timeoutDuration: Duration,
 *   cache: Cache,
 * )
 * ```
 *
 * In this scenario, you would then also define a [AssistedFactory]-annotated type (usually a nested
 * class) to create this. This factory's requirements are defined on the [AssistedFactory] kdoc.
 *
 * ```
 * @AssistedInject
 * class HttpClient(
 *   @Assisted timeoutDuration: Duration,
 *   cache: Cache,
 * ) {
 *   @AssistedFactory
 *   fun interface Factory {
 *     fun create(timeoutDuration: Duration): HttpClient
 *   }
 * }
 * ```
 *
 * This factory can then be requested as a dependency from the graph and used to instantiate new
 * `HttpClient` instances.
 *
 * ```
 * @DependencyGraph
 * interface AppGraph {
 *   val httpClientFactory: HttpClient.Factory
 * }
 *
 * fun main() {
 *   val httpClientFactory = createGraph<AppGraph>().httpClientFactory
 *   val httpClient = httpClientFactory.create(userPrefs.requestTimeoutDuration)
 * }
 * ```
 *
 * You can (and usually would!) access this dependency in any other injection site too.
 *
 * **Note**: Assisted injected types cannot be scoped and can only be instantiated by associated
 * [AssistedFactory] types.
 *
 * See the docs on [Assisted] and [AssistedFactory] for more details on their use.
 *
 * ## Top-Level Function Injection
 *
 * Assisted injection is supported in top-level function injection, no need to use `@AssistedInject`
 * annotation. See [Inject] for more details.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
public annotation class AssistedInject
