// A child graph extension's @Binds should supersede a parent's @Provides of the
// same type. Previously this caused a DuplicateBinding error because
// collectInheritedData only checked node.providerFactories, not node.bindsCallables.
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

  @Provides fun provideString(): String = "hello"

  @Binds val String.bindAsNullable: String?

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals(null, parent.nullableString)
  val child = parent.childGraphFactory().create()
  assertEquals("hello", child.presentString)
  return "OK"
}
