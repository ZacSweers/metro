// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// The child implements the same supertype and declares the same injector as its parent. Neither is
// an explicit binding, so neither is an override.

interface BaseGraph

class Target {
  @Inject lateinit var string: String
}

@DependencyGraph
interface ParentGraph : BaseGraph {
  fun inject(target: Target)

  @Provides fun provideString(): String = "parent"

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph : BaseGraph {
  fun inject(target: Target)

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
