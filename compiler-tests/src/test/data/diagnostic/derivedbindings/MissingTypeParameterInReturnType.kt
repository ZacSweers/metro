// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

interface Cache<T>

class LruCache<T> : Cache<T>

@BindingContainer
object EncoderBindings {
  @Provides fun <<!METRO_TYPE_PARAMETERS_ERROR!>T<!>> unused(element: Encoder<T>): Encoder<String> = object : Encoder<String> {}

  @Provides
  fun <K, <!METRO_TYPE_PARAMETERS_ERROR!>V<!>> mapKeyOnly(key: Encoder<K>, value: Encoder<V>): Encoder<Map<K, String>> =
    object : Encoder<Map<K, String>> {}

  @Provides
  fun <T> nested(element: Encoder<T>): Encoder<Map<String, List<Set<T>>>> =
    object : Encoder<Map<String, List<Set<T>>>> {}
}

@BindingContainer
interface CacheBindings {
  @Binds fun <<!METRO_TYPE_PARAMETERS_ERROR!>T<!>> lru(impl: LruCache<String>): Cache<String>
}
