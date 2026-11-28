// RUN_PIPELINE_TILL: BACKEND

// The default NONE severity reports nothing for overrides of inherited bindings.

@DependencyGraph
interface ParentGraph {
  val string: String
  val int: Int

  @Provides fun provideString(): String = "parent"

  @Provides fun provideInt(): Int = 1

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String
  val int: Int

  @Provides fun provideString(): String = "child"

  @Provides fun provideInt(): Int = 2

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
