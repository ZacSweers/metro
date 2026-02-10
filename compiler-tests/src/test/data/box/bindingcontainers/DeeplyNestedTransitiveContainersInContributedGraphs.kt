// Verify that deeply nested transitive binding containers (3 levels: A includes B,
// B includes C) are properly resolved in contributed graph extensions. This exercises
// the BFS transitive resolution and caching logic.
// https://github.com/ZacSweers/metro/pull/883

@ContributesTo(AppScope::class)
@BindingContainer(includes = [Level2Bindings::class])
interface Level1Bindings {
  @Binds val Int.bindNumber: Number
}

@BindingContainer(includes = [Level3Bindings::class])
interface Level2Bindings {
  @Binds val String.bindCharSequence: CharSequence
}

@BindingContainer
interface Level3Bindings {
  companion object {
    @Provides fun provideLong(): Long = 42L
  }
}

@GraphExtension(AppScope::class)
interface ContributedGraph {
  val number: Number
  val charSequence: CharSequence
  val long: Long

  @Provides fun provideString(): String = "hello"
  @Provides fun provideInt(): Int = 3

  @GraphExtension.Factory @ContributesTo(Unit::class)
  interface Factory {
    fun createContributedGraph(): ContributedGraph
  }
}

@DependencyGraph(Unit::class) interface UnitGraph

fun box(): String {
  val graph = createGraph<UnitGraph>().createContributedGraph()
  assertEquals("hello", graph.charSequence)
  assertEquals(3, graph.number)
  assertEquals(42L, graph.long)
  return "OK"
}
