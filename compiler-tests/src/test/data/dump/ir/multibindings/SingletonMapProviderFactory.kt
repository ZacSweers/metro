// A provider of a single-entry provider-valued map should reuse its entry provider and build a
// fresh map on each invocation.

@DependencyGraph
interface AppGraph {
  @Provides fun provideValue(): Int = 1

  @Binds @IntoMap @IntKey(1) fun Int.bindValue(): Int

  val ints: () -> Map<Int, () -> Int>
}
