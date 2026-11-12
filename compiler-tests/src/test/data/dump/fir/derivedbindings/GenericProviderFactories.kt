// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

@BindingContainer
object Bindings {
  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = object : Encoder<List<T>> {}

  @Provides
  fun <T : Comparable<T>> sorted(element: Encoder<T>): Encoder<Set<T>> =
    object : Encoder<Set<T>> {}

  @Provides fun <T> noDependencies(): Encoder<Collection<T>> = object : Encoder<Collection<T>> {}
}

@BindingContainer
class GenericContainer<A> {
  @Provides
  fun <B : A> combined(a: Encoder<A>, b: Encoder<B>): Encoder<Map<A, B>> =
    object : Encoder<Map<A, B>> {}

  @Provides
  fun <A> shadowed(a: Encoder<A>): Encoder<List<A>> = object : Encoder<List<A>> {}
}
