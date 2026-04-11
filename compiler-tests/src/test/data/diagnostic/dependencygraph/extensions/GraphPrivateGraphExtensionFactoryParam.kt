// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ParentGraph : ChildGraph.Factory

@GraphExtension
interface ChildGraph {
  val <!PRIVATE_BINDING_ERROR!>text<!>: String

  @GraphExtension.Factory
  interface Factory {
    fun create(@GraphPrivate @Provides text: String): ChildGraph
  }
}
