@DependencyGraph
interface AppGraph {
  val ints: Set<Int>

  @Provides
  @ElementsIntoSet
  fun provideInts(): Set<Int> = setOf(1, 2, 3)
}