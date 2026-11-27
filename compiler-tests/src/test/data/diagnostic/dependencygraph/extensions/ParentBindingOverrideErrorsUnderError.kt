// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// An override of an inherited binding is an error under ERROR.

@DependencyGraph
interface ParentGraph {
  val string: String

  @Provides fun provideString(): String = "parent"

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String

  @Provides fun <!PARENT_BINDING_OVERRIDE_ERROR!>provideString<!>(): String = "child"

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
