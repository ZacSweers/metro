// A child graph extension's @Provides factory parameter should supersede a parent's
// @Provides of the same type. The factory param is a @BindsInstance that should take
// priority over the inherited parent binding.
// https://github.com/ZacSweers/metro/issues/1885

@DependencyGraph
interface ParentGraph {
  val nullableString: String?

  @Provides fun provideNullString(): String? = null

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val presentString: String?

  @GraphExtension.Factory
  interface Factory {
    fun create(@Provides nullableString: String?): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals(null, parent.nullableString)
  val child = parent.childGraphFactory().create("from factory")
  assertEquals("from factory", child.presentString)
  return "OK"
}
