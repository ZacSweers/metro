// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Ensures we don't try to mis-report indirect upstream modules as not visible

// MODULE: core
interface Base

// MODULE: lib1(core)
@Inject
@ContributesBinding(String::class)
class FooImpl : Base

// MODULE: main(lib1 core)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!METRO_ERROR!>base<!>: Base

  val stringGraph: StringGraph
}

@GraphExtension(String::class)
interface StringGraph