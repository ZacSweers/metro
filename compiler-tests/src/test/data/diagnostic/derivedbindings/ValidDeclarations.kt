// ENABLE_DERIVED_BINDINGS
// ENABLE_SUSPEND_PROVIDERS
// LANGUAGE: +ContextParameters

interface Encoder<T>

interface Cache<T>

class LruCache<T> : Cache<T>

class Label(val value: String)

@BindingContainer
object ContainerBindings {
  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = object : Encoder<List<T>> {}

  @Provides
  fun <K, V> map(key: Encoder<K>, value: Encoder<V>): Encoder<Map<K, V>> =
    object : Encoder<Map<K, V>> {}

  @Provides
  fun <T : Any> nullable(element: Encoder<T>): Encoder<T?> = object : Encoder<T?> {}

  @Provides
  @Named("qualified")
  fun <T> qualified(element: Encoder<T>): Encoder<Set<T>> = object : Encoder<Set<T>> {}

  @Provides
  @SingleIn(AppScope::class)
  private fun <T> scoped(element: Encoder<T>): Encoder<Collection<T>> =
    object : Encoder<Collection<T>> {}

  @Provides
  context(label: Label)
  fun <T> labeled(element: Encoder<T>): Encoder<Array<T>> = object : Encoder<Array<T>> {}

  @Provides
  suspend fun <T> suspending(element: Encoder<T>): Encoder<Sequence<T>> =
    object : Encoder<Sequence<T>> {}

  @Provides fun <T> Encoder<T>.extension(): Encoder<Iterable<T>> = object : Encoder<Iterable<T>> {}
}

@BindingContainer
interface CacheBindings {
  @Binds fun <T> lru(impl: LruCache<T>): Cache<T>

  @Binds fun <T> LruCache<T>.bind(): Cache<T>

  @Binds @Named("qualified") fun <T : Any> qualifiedLru(impl: LruCache<T>): Cache<T>
}

@DependencyGraph
interface AppGraph {
  @Provides fun <T> pair(element: Encoder<T>): Encoder<Pair<T, T>> = object : Encoder<Pair<T, T>> {}

  companion object {
    @Provides
    fun <T> triple(element: Encoder<T>): Encoder<Triple<T, T, T>> =
      object : Encoder<Triple<T, T, T>> {}
  }
}

@BindingContainer
class GenericContainer<A> {
  @Provides fun <B> combined(a: Encoder<A>, b: Encoder<B>): Encoder<Map<A, B>> =
    object : Encoder<Map<A, B>> {}
}
