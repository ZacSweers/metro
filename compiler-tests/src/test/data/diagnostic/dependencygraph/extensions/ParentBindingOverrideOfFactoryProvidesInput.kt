// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// Overriding a parent graph factory's @Provides input is reported.

@DependencyGraph
interface ParentGraph {
  val string: String

  fun childGraphFactory(): ChildGraph.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides string: String): ParentGraph
  }
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
