// A child graph extension's @Provides should supersede a parent's @Binds of the
// same type. Previously this could cause a DuplicateBinding error because
// collectInheritedData only checked node.bindsCallables, not node.providerFactories.
// https://github.com/ZacSweers/metro/issues/1885

@DependencyGraph
interface ParentGraph {
  val nullableString: String?

  @Provides fun provideString(): String = "parent"

  @Binds val String.bindAsNullable: String?

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val nullableString: String?

  @Provides fun provideNullableString(): String? = "child"

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertEquals("parent", parent.nullableString)
  val child = parent.childGraphFactory().create()
  assertEquals("child", child.nullableString)
  return "OK"
}
