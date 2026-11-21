// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

interface Missing

@Inject class Root(val first: First)
@Inject class First(val second: Second)
@Inject class Second(<!MISSING_BINDING!>val missing: Missing<!>)

@DependencyGraph
interface ExampleGraph {
  val root: Root

  @OptionalBinding
  val fallback: Missing get() = object : Missing {}
}
