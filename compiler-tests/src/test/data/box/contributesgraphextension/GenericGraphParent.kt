@GraphExtension
interface ChildGraph {
  val value: String

  @GraphExtension.Factory
  interface Factory {
    fun make(): ChildGraph
  }
}

@DependencyGraph
interface ParentGraph<T> : ChildGraph.Factory {
  val value: T

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: String): ParentGraph<String>
  }
}

@DependencyGraph
interface GenericGraph<T>

@DependencyGraph
interface NestedTypeGraph<T> {
  val value: T

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: List<String>): NestedTypeGraph<List<String>>
  }
}

@GraphExtension
interface UnspecializedChildGraph {
  val value: String

  @GraphExtension.Factory
  interface Factory {
    fun make(): UnspecializedChildGraph
  }
}

@DependencyGraph
interface UnspecializedParentGraph<T> {
  val childFactory: UnspecializedChildGraph.Factory

  @Provides fun provideValue(): String = "unspecialized"
}

fun box(): String {
  val parent = createGraphFactory<ParentGraph.Factory>().create("specific")
  assertEquals("specific", parent.value)
  assertEquals("specific", parent.make().value)
  assertNotNull(createGraph<GenericGraph<Int>>())
  val nested = createGraphFactory<NestedTypeGraph.Factory>().create(listOf("nested"))
  assertEquals(listOf("nested"), nested.value)
  val unspecialized = createGraph<UnspecializedParentGraph<Int>>()
  val child = unspecialized.childFactory.make()
  assertEquals("unspecialized", child.value)
  return "OK"
}
