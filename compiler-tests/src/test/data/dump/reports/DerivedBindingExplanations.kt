// RUN_PIPELINE_TILL: BACKEND
// ENABLE_DERIVED_BINDINGS
// Generated child accessors have different source offsets across Kotlin versions.
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-AppGraph-Impl-ChildGraphImpl.json

interface Encoder<T>

class NamedEncoder<T>(val name: String) : Encoder<T>

interface Cache<T>

@Inject class LruCache<T> : Cache<T>

class User

class Token

@BindingContainer
object Encoders {
  // This exact binding beats `anything` for `Encoder<String>`.
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

  // This beats `anything` for `Encoder<List<User>>` because its return type is more specific.
  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list")

  // Only the child graph requests this, so the child redirects it to the parent that owns the scope.
  @Provides
  @SingleIn(AppScope::class)
  fun <K, V> map(keys: Encoder<K>, values: Encoder<V>): Encoder<Map<K, V>> = NamedEncoder("map")
}

@BindingContainer
interface CacheBindings {
  @Binds fun <T> lru(impl: LruCache<T>): Cache<T>
}

@DependencyGraph(AppScope::class, bindingContainers = [Encoders::class, CacheBindings::class])
interface AppGraph {
  val users: Encoder<List<User>>
  val string: Encoder<String>
  val userCache: Cache<User>
  val child: ChildGraph
}

@GraphExtension
interface ChildGraph {
  val tokensByName: Encoder<Map<String, Token>>
}
