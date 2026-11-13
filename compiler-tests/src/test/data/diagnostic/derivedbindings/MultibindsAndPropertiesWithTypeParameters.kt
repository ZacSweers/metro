// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Cache<T>

class LruCache<T> : Cache<T>

interface ExampleGraph {
  @Multibinds fun <!METRO_TYPE_PARAMETERS_ERROR!><T><!> caches(): Set<Cache<T>>
}

@BindingContainer
interface PropertyBindings {
  @Binds val <!METRO_TYPE_PARAMETERS_ERROR!><T><!> LruCache<T>.bind: Cache<T>
}
