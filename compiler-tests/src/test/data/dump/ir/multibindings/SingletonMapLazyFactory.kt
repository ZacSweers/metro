// A single-entry Map<K, Lazy<V>> multibinding accessed through a Provider should emit
// `MapLazyFactory.singleton(key, provider)` rather than building a Builder.

@DependencyGraph
interface AppGraph {
  @Provides fun provideValue(): Int = 1

  @Binds @IntoMap @IntKey(1) fun Int.bindValue(): Int

  val ints: () -> Map<Int, Lazy<Int>>
}
