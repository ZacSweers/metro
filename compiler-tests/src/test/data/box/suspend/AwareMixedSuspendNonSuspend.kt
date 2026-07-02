// `@SuspendAware` class with a mix of suspend and non-suspend dependencies. Non-suspend deps
// are still held as `SuspendProvider<T>` ctor fields — the graph wraps the underlying
// `Provider<T>` in `CompositeProvider` (allocation-free intrinsic) to satisfy the field type.

@SuspendAware
@Inject
class AccountCreator(val database: String, val region: Long)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  // suspend
  @Provides suspend fun provideDatabase(): String = "db"

  // non-suspend (this is the CompositeProvider path)
  @Provides fun provideRegion(): Long = 42L
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
