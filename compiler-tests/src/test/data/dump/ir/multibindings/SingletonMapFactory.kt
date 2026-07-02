// A single-entry Map<K, V> multibinding accessed through a Provider should emit
// `MapFactory.singleton(key, provider)` rather than building a Builder.

@DependencyGraph
interface AppGraph {
  @Provides fun provideValue(): Int = 1

  @Binds @IntoMap @IntKey(1) fun Int.bindValue(): Int

  val ints: () -> Map<Int, Int>
}
