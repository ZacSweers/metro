// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Regression test for https://github.com/ZacSweers/metro/issues/1729

@Inject
class Foo

@Inject
class View(<!METRO_ERROR!>foo: Foo?<!>)

@DependencyGraph
interface AppGraph {
  val view: View
}
