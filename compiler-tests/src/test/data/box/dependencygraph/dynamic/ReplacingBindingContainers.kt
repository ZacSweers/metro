@BindingContainer
class IntProvider(private val value: Int) {
  @Provides fun provideInt(): Int = value
}

@BindingContainer
class LongProvider {
  @Provides fun provideLong(): Long = 3L
}

@BindingContainer
class TestLongProvider(private val value: Long) {
  @Provides fun provideLong(): Long = value
}

@DependencyGraph(
  // Provides a string that'll be replaced
  bindingContainers = [LongProvider::class]
)
interface AppGraph {
  val int: Int
  val long: Long

  @DependencyGraph.Factory
  interface Factory {
    // By default this provides 3
    fun create(@Includes intProvider: IntProvider): AppGraph
  }
}

class Example {
  val propGraph =
    createDynamicGraphFactory<AppGraph.Factory>(IntProvider(4), TestLongProvider(4L))
      .create(IntProvider(3))

  fun createTestGraph(value: Int): AppGraph {
    // Graph in a class
    val testGraph =
      createDynamicGraphFactory<AppGraph.Factory>(
          IntProvider(value),
          TestLongProvider(value.toLong()),
        )
        .create(IntProvider(3))
    return testGraph
  }
}

fun box(): String {
  assertEquals(2, Example().createTestGraph(2).int)
  assertEquals(2L, Example().createTestGraph(2).long)
  assertEquals(4, Example().propGraph.int)
  assertEquals(4L, Example().propGraph.long)
  val topLevelGraph =
    createDynamicGraphFactory<AppGraph.Factory>(IntProvider(5), TestLongProvider(5L))
      .create(IntProvider(3))
  // top-level function-only
  assertEquals(5, topLevelGraph.int)
  assertEquals(5L, topLevelGraph.long)
  return "OK"
}
