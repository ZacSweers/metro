// A provider of a single-entry map of lazy providers should build a fresh map on each invocation.

@DependencyGraph
interface AppGraph {
  @Provides fun provideValue(): Int = 1

  @Binds @IntoMap @IntKey(1) fun Int.bindValue(): Int

  val ints: () -> Map<Int, () -> Lazy<Int>>
}
