/*
 * Copyright (C) 2020 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.annotations

/**
 * Annotates the constructor of a type that will be created via assisted injection.
 *
 * Note that an assisted injection type cannot be scoped. In addition, assisted injection requires
 * the use of a factory annotated with [AssistedFactory] (see the example below).
 *
 * Example usage:
 *
 * Suppose we have a type, `DataService`, that has two dependencies: `DataFetcher` and `Config`.
 * When creating `DataService`, we would like to pass in an instance of `Config` manually rather
 * than having Dagger create it for us. This can be done using assisted injection.
 *
 * To start, we annotate the `DataService` constructor with [AssistedInject] and we annotate the
 * `Config` parameter with [Assisted], as shown below:
 * ```
 * class DataService(
 *   private val dataFetcher: DataFetcher;
 *   @Assisted private val config: Config;
 * )
 * ```
 *
 * Next, we define a factory for the assisted type, `DataService`, and annotate it with
 * [AssistedFactory]. The factory must contain a single abstract, non-default method which takes in
 * all the assisted parameters (in order) and returns the assisted type.
 *
 * ```
 * @AssistedFactory
 * interface DataServiceFactory {
 *   create(config: Config): DataService
 * }
 * ```
 *
 * Dagger will generate an implementation of the factory and bind it to the factory type. The
 * factory can then be used to create an instance of the assisted type:
 * ```
 * @Inject
 * class MyApplication(dataServiceFactory: DataServiceFactory) {
 *   val dataService = dataServiceFactory.create(Config(...));
 * }
 * ```
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CONSTRUCTOR)
public annotation class AssistedInject
