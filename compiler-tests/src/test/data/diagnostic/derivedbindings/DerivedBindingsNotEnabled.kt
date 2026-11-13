// RENDER_DIAGNOSTICS_FULL_TEXT

interface Encoder<T>

class LruCache<T>

interface Cache<T>

@BindingContainer
object EncoderBindings {
  @Provides fun <<!DERIVED_BINDINGS_NOT_ENABLED!>T<!>> list(element: Encoder<T>): Encoder<List<T>> = object : Encoder<List<T>> {}

  @Provides
  fun <<!DERIVED_BINDINGS_NOT_ENABLED!>K<!>, V> map(key: Encoder<K>, value: Encoder<V>): Encoder<Map<K, V>> =
    object : Encoder<Map<K, V>> {}
}

@BindingContainer
interface CacheBindings {
  @Binds fun <<!DERIVED_BINDINGS_NOT_ENABLED!>T<!>> lru(impl: LruCache<T>): Cache<T>
}
