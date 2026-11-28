// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// ChildGraph overrides ParentGraph's binding. GrandchildGraph declares nothing, so the override is
// reported once, on ChildGraph.

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

  fun grandchildGraphFactory(): GrandchildGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun create(): GrandchildGraph
  }
}
