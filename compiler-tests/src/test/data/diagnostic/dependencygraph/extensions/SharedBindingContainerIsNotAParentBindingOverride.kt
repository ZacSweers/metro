// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// A binding container included by both the parent and the child declares the same binding at both
// levels. That isn't an override.

@BindingContainer
object SharedBindings {
  @Provides fun provideString(): String = "shared"
}

@DependencyGraph(bindingContainers = [SharedBindings::class])
interface ParentGraph {
  val string: String

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension(bindingContainers = [SharedBindings::class])
interface ChildGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
