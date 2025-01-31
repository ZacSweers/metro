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

/**
 * Marks an annotated annotation class as a _scope_ annotation.
 *
 * Scopes are used to limit the number of instances of an injected type within a given graph. By
 * default, bindings provided by [Provides] or constructor-injected classes are recreated every time
 * they are injected. If you annotate a class or provider with a scope annotation however, that will
 * enforce that only a single instance is ever (lazily) created within that graph. In this event,
 * the consuming graph must also be annotated with the same scope annotation. This initialization is
 * thread-safe.
 *
 * Metro offers a convenience [SingleIn] scope + [AppScope] for common usage. This is functionally
 * equivalent to `@Singleton` from JSR 330 and other dependency injection APIs.
 *
 * ```
 * @SingleIn(AppScope::class)
 * @Inject
 * class HttpClient(...)
 *
 * @SingleIn(AppScope::class)
 * @DependencyGraph
 * interface AppGraph {
 *   val httpClient: HttpClient
 * }
 * ```
 *
 * In the above example, every call to `AppGraph.httpClient` will always return the same instance.
 *
 * **Notes**
 * - Only one scope annotation is allowed per injected class or provider. More than one is
 *   considered an error.
 * - Dependency graphs may declare _multiple_ scopes that they support.
 * - It is an error for an unscoped dependency graph to reference scoped bindings
 * - It is an error for a scoped dependency graph to reference scoped bindings with different scopes
 *   than it claims to support.
 *
 * @see SingleIn
 * @see DependencyGraph
 */
@Target(AnnotationTarget.ANNOTATION_CLASS) public annotation class Scope
