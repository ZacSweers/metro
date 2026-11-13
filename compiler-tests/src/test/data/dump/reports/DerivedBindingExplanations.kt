// RUN_PIPELINE_TILL: BACKEND
// ENABLE_DERIVED_BINDINGS
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json

interface Encoder<T>

class NamedEncoder<T>(val name: String) : Encoder<T>

interface Cache<T>

@Inject class LruCache<T> : Cache<T>

class User

@BindingContainer
object Encoders {
  // This exact binding beats `anything` for `Encoder<String>`.
  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  @Provides fun <T> anything(): Encoder<T> = NamedEncoder("anything")

  // This beats `anything` for `Encoder<List<User>>` because its return type is more specific.
  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list")
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
}
