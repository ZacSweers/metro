// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
abstract class ParentScope
abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {
  @GraphExtension.Factory
  @ContributesTo(ParentScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
@DependencyGraph(ParentScope::class, excludes = [ChildGraph.Factory::class])
interface ParentGraph {
  val <!MISSING_BINDING!>childGraphFactory<!>: ChildGraph.Factory
}
