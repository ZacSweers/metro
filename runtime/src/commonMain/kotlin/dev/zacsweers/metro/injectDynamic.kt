package dev.zacsweers.metro

/**
 * This is just an intrinsic shim to call [dynamicInjector] + injectMembers() on it with [T].
 *
 * - This may be called in a member function body or property initializer. It is not legal in
 *   top-level functions.
 */
@Suppress("UnusedReceiverParameter")
public inline fun <reified T : Any> T.injectDynamic(@Suppress("unused") vararg args: Any) {
  throw UnsupportedOperationException("Implemented by the compiler")
}

/**
 * Injects [T] with the given set of input [args]. Args may be `@Provides` instance arguments or
 * `@Includes` arguments (either graph dependencies or binding containers).
 *
 * Type [T] must have member injections that will act as roots.
 *
 * ```kotlin
 * class AppTest {
 *   @Inject lateinit var httpClient: HttpClient
 *
 *   @Before
 *   fun setup() {
 *     injectDynamic(FakeBindings)
 *   }
 *
 *   @Test
 *   fun test() {
 *     // ...
 *   }
 *
 *   @BindingContainer
 *   object FakeBindings {
 *     @Provides fun provideFakeClient(): HttpClient = FakeHttpClient()
 *   }
 * }
 * ```
 *
 * The compiler will generate an adhoc, dynamic graph within the enclosing class that is unique to
 * the combination of input [args] and target type [T].
 *
 * These should be used with care and are generally reserved for tests.
 *
 * **Constraints**
 * - It's an error to pass no args.
 * - All args must be non-local, canonical classes. i.e., they must be something with a name!
 * - This may be called in a member function body, top-level function body, or property initializer.
 */
public inline fun <reified T : Any> dynamicInjector(
  @Suppress("unused") vararg args: Any
): MembersInjector<T> {
  throw UnsupportedOperationException("Implemented by the compiler")
}

/**
 * Creates a factory function of [T] with the given set of input [args]. Args may be `@Provides`
 * instance arguments or `@Includes` arguments (either graph dependencies or binding containers).
 *
 * Type [T] must be constructor-injected or assisted-injected. It may also have member injections,
 * though if it only has member injections then you should use [injectDynamic] or [dynamicInjector].
 *
 * ```kotlin
 * @Inject
 * class AppTest(val httpClient: HttpClient) {
 *
 *   @Test
 *   fun test() {
 *     // ...
 *   }
 *
 *   @BindingContainer
 *   object FakeBindings {
 *     @Provides fun provideFakeClient(): HttpClient = FakeHttpClient()
 *   }
 *
 *   companion object {
 *     fun instantiateTest() : AppTest = dynamicFactory(FakeBindings).invoke()
 *   }
 * }
 * ```
 *
 * The compiler will generate an adhoc, dynamic graph within the enclosing class that is unique to
 * the combination of input [args] and target type [T].
 *
 * These should be used with care and are generally reserved for tests. This helper in particular is
 * useful for test runners to instantiate injected test classes.
 *
 * **Constraints**
 * - It's an error to pass no args.
 * - All args must be non-local, canonical classes. i.e., they must be something with a name!
 * - This may be called in a member function body, top-level function body, or property initializer.
 */
public inline fun <reified T : Any> dynamicFactory(@Suppress("unused") vararg args: Any): () -> T {
  throw UnsupportedOperationException("Implemented by the compiler")
}
