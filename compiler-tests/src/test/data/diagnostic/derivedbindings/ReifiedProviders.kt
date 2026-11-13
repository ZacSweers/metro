// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS
// PUBLIC_SCOPED_PROVIDER_SEVERITY: ERROR

interface Encoder<T>

class TypedEncoder<T>(val name: String) : Encoder<T>

@Suppress("NOTHING_TO_INLINE")
@BindingContainer
object Bindings {
  @Provides
  inline fun <reified T> publicReified(): Encoder<List<T>> =
    TypedEncoder(T::class.toString())

  @PublishedApi
  @Provides
  internal inline fun <reified T> publishedApiReified(): Encoder<Set<T>> =
    TypedEncoder(T::class.toString())

  @Provides
  internal <!REIFIED_PROVIDES_ERROR!>inline<!> fun <reified T> internalReified(): Encoder<Collection<T>> =
    TypedEncoder(T::class.toString())

  @Provides
  private <!REIFIED_PROVIDES_ERROR!>inline<!> fun <reified T> privateReified(): Encoder<Iterable<T>> =
    TypedEncoder(T::class.toString())

  @Provides
  @SingleIn(AppScope::class)
  inline fun <reified T> scopedReified(): Encoder<Sequence<T>> =
    TypedEncoder(T::class.toString())
}

// The container is internal without `@PublishedApi`, so the provider isn't published either.
@Suppress("NOTHING_TO_INLINE")
@BindingContainer
internal object InternalBindings {
  @PublishedApi
  @Provides
  internal <!REIFIED_PROVIDES_ERROR!>inline<!> fun <reified T> publishedInInternalContainer(): Encoder<Array<T>> =
    TypedEncoder(T::class.toString())
}

// A `@PublishedApi internal` container publishes its public and `@PublishedApi` members.
@Suppress("NOTHING_TO_INLINE")
@PublishedApi
@BindingContainer
internal object PublishedBindings {
  @Provides
  inline fun <reified T> publicInPublishedContainer(): Encoder<Map<String, T>> =
    TypedEncoder(T::class.toString())

  @PublishedApi
  @Provides
  internal inline fun <reified T> publishedInPublishedContainer(): Encoder<Map<Int, T>> =
    TypedEncoder(T::class.toString())
}
