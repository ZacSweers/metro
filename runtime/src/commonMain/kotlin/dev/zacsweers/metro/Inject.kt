// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * This annotation is used to indicate that the annotated declaration expects to have its
 * dependencies injected. This is applicable to multiple targets, the primary form being
 * **constructor injection**.
 *
 * ```
 * class HttpClient @Inject constructor(cache: Cache)
 * ```
 *
 * For simple cases with single primary constructor, you may also just annotate the class
 * declaration directly.
 *
 * ```
 * @Inject
 * class HttpClient(cache: Cache)
 * ```
 *
 * In this scenario, Metro will manage instantiation of an `HttpClient` instance and fulfill its
 * dependencies, which are denoted as parameters to the injected declaration. In this case - `cache:
 * Cache` is a dependency that must be provided elsewhere in the [DependencyGraph] that this class
 * is ultimately used in.
 *
 * Constructor injection should be the default injection mechanism where possible, as it affords the
 * most flexibility and allows Metro to manage the lifecycle of the created class.
 *
 * ### [Provider] and [Lazy]
 *
 * Injected dependency parameter types may be wrapped in [Provider] or [Lazy] and Metro will enforce
 * these contracts in its generated code.
 *
 * ```
 * @Inject
 * class HttpClient(
 *   // A Cache instance will be lazily provided each time Provider.invoke() is called
 *   providedCache: Provider<Cache>,
 *   // A lazily-computed Cache instance will be created whenever Lazy.value is called
 *   lazyCache: Lazy<Cache>,
 * )
 * ```
 *
 * **Note:** The contract of [Lazy] is that it will _only_ be lazy to the scope of the class. To
 * make a dependency a singleton within a given graph, you must use a [Scope] like [SingleIn].
 *
 * ### Compiler-generated Factories
 *
 * To fully support reuse and code de-duplication in modularized codebases or libraries, you _must_
 * run the Metro compiler over any injected class during its compilation.
 *
 * ## Assisted Injection
 *
 * Assisted injection is a type of injection where some dependencies may be fulfill by the host
 * dependency graph but some others may be _assisted_ at runtime during instantiation. This is
 * useful for deferring some inputs to dynamic values computed at runtime.
 *
 * For example, our `HttpClient` example above may accept a user-preferenced timeout duration.
 *
 * ```
 * @Inject
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
 * @Inject
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
 * ## Member Injection
 *
 * If for some reason you cannot constructor-inject a class, you can also use _member injection_ to
 * inject properties and functions. An example use case for this is a class instantiated by a
 * framework that you don't control, such as an Android `Activity` on older versions of Android.
 *
 * ```
 * class HomeActivity {
 *   @Inject lateinit var httpClient: HttpClient
 *
 *   @Inject fun setHttpClient(httpClient: HttpClient)
 * }
 * ```
 *
 * To perform injection on these classes, you must define an _injector_ function on the dependency
 * graph that injects them. The structure of this function is it must have exactly one parameter
 * whose type is the class with injected members or a subclass of it (more on that lower down).
 *
 * ```
 * @DependencyGraph
 * interface AppGraph {
 *   fun inject(activity: HomeActivity)
 * }
 * ```
 *
 * Metro will generate code to inject these members when this function is called.
 *
 * Alternatively, a [MembersInjector] instance typed to the injected class can be requested from the
 * DI graph and called ad-hoc.
 *
 * ```
 * @DependencyGraph
 * interface AppGraph {
 *   val homeActivityInjector: MembersInjector<HomeActivity>
 * }
 * ```
 *
 * **Notes**
 * - Only _classes_ may have member injections. Interfaces, objects, enums, and annotations may not.
 * - The same behaviors apply regarding use of [Provider], [Lazy], and [qualifiers][Qualifier].
 * - Injected members may (and likely should!) also be `private`.
 *
 * In non-final classes, subclasses with member injections will automatically also perform member
 * injection on superclass members (in order of oldest -> newest class hierarchy order).
 *
 * Member injection _may_ be used in combination with constructor injection. In this scenario, Metro
 * will instantiate the class via its injected constructor and then immediately perform member
 * injection post-construction.
 *
 * ```
 * @Inject
 * class HttpClient(cache: Cache) {
 *   @Inject fun setSocketFactory(factory: SocketFactory)
 * }
 * ```
 *
 * **Note:**: This is primarily for superclass and function injection though, Metro will emit a
 * compiler warning on injected declared properties to nudge you toward moving them to the injected
 * constructor.
 *
 * ## Top-Level Function Injection
 *
 * Metro supports top-level function injection behind an opt-in compiler option. This is
 * particularly useful for applications that run from a `main` function or Composable functions.
 *
 * ```
 * @Inject
 * fun App(settings: Settings) {
 *   // Run app with injected Settings
 * }
 * ```
 *
 * Metro will generate a wrapper class with the name `{function name}Class` that you can access from
 * a dependency graph and invoke.
 *
 * ```
 * @DependencyGraph
 * interface AppGraph {
 *   // Available accessor
 *   val app: AppClass
 * }
 *
 * fun main() {
 *   val app = createGraph<AppGraph>().app
 *   app()
 * }
 *
 * // Generated by Metro at compile-time
 * class AppClass @Inject constructor(private val settings: Settings) {
 *   operator fun invoke() = App(settings)
 * }
 * ```
 *
 * If you have any assisted parameters, simply annotate them with [@Assisted][Assisted] and Metro
 * will add these to the generated `invoke` function.
 *
 * ```
 * @Inject
 * fun App(settings: Settings, @Assisted theme: Theme) {
 *   // Run app with injected settings and theme
 * }
 *
 * fun main() {
 *   val app = createGraph<AppGraph>().app
 *   app(Theme.Dark)
 * }
 * ```
 *
 * ### `suspend`
 *
 * If your function is a `suspend` function, that modifier will be mirrored in the generated
 * `invoke` function.
 *
 * ```
 * @Inject
 * suspend fun App(settings: Settings) {
 *   // Run app with injected settings and theme
 * }
 *
 * suspend fun main() {
 *   val app = createGraph<AppGraph>().app
 *   app() // <-- ⚡︎ suspend call ⚡︎
 * }
 * ```
 *
 * ### Compose
 *
 * If your function is annotated with `@Composable` from Jetpack Compose, that annotation will be
 * mirrored in the generated `invoke` function.
 *
 * ```
 * @Inject
 * @Composable
 * fun App(settings: Settings) {
 *   // Run compose app with injected settings and theme
 * }
 *
 * suspend fun main() {
 *   val app = createGraph<AppGraph>().app
 *   app() // <-- composable call
 * }
 * ```
 *
 * In this case, the generated `AppClass` will also be annotated with `@Stable` for stability.
 *
 * ### Why opt-in?
 *
 * There are two reasons this is behind an opt-in option at the moment.
 * 1. Generating top-level declarations in Kotlin compiler plugins (in FIR specifically) is not
 *    currently compatible with incremental compilation.
 * 2. IDE support is rudimentary at best and currently requires enabling a custom registry flag.
 *    TODO link registry flag docs.
 *
 * Because of this, it's likely better for now to just hand-write the equivalent class that Metro
 * generates. If you still wish to proceed with using this, it can be enabled via the Gradle DSL.
 *
 * ```
 * metro {
 *   enableTopLevelFunctionInjection.set(true)
 * }
 * ```
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.PROPERTY_SETTER,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.FIELD,
)
public annotation class Inject
