// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: WARN

// Under WARN, each override of an inherited binding is a warning and the graph still compiles.

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

  @Provides fun <!PARENT_BINDING_OVERRIDE_WARNING!>provideString<!>(): String = "child"

  @Provides fun <!PARENT_BINDING_OVERRIDE_WARNING!>provideInt<!>(): Int = 2

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
