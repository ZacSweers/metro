// A dynamic container that provides a whole Set or Map replaces the graph's multibinding for that
// key. The graph's own contributions are dropped.
@DependencyGraph
interface AppGraph {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Provides @IntoSet fun provideString(): String = "real"

  @Provides @IntoMap @StringKey("real") fun provideInt(): Int = 1

  fun child(): ChildGraph
}

// A graph extension of the dynamic graph sees the same replacement next to its own contribution.
@GraphExtension
interface ChildGraph {
  val strings: Set<String>

  @Provides @IntoSet fun provideChildString(): String = "child"
}

@BindingContainer
object FakeCollections {
  @Provides fun provideStrings(): Set<String> = setOf("fake")

  @Provides fun provideInts(): Map<String, Int> = mapOf("fake" to 2)
}

fun box(): String {
  val graph = createDynamicGraph<AppGraph>(FakeCollections)
  assertEquals(setOf("fake"), graph.strings)
  assertEquals(mapOf("fake" to 2), graph.ints)
  assertEquals(setOf("fake"), graph.child().strings)
  return "OK"
}
