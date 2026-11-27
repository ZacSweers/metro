// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// A dynamic graph's replacement binding isn't an inherited binding that the child overrides.

@DependencyGraph
interface ParentGraph {
  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String

  @Provides fun provideString(): String = "child"

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@BindingContainer
object FakeBindings {
  @Provides fun provideString(): String = "dynamic"
}

fun createChild(): ChildGraph {
  return createDynamicGraph<ParentGraph>(FakeBindings).childGraphFactory().create()
}
