// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS
// PUBLIC_SCOPED_PROVIDER_SEVERITY: NONE

interface Encoder<T>

@Suppress("NOTHING_TO_INLINE")
@BindingContainer
object Bindings {
  @Provides
  @SingleIn(AppScope::class)
  <!INLINE_PROVIDES_WARNING!>inline<!> fun <T> scoped(element: Encoder<T>): Encoder<List<T>> =
    object : Encoder<List<T>> {}

  @Provides
  private <!INLINE_PROVIDES_WARNING!>inline<!> fun <T> private(element: Encoder<T>): Encoder<Set<T>> =
    object : Encoder<Set<T>> {}

  @Provides
  internal <!INLINE_PROVIDES_WARNING!>inline<!> fun <T> internal(element: Encoder<T>): Encoder<Collection<T>> =
    object : Encoder<Collection<T>> {}
}
