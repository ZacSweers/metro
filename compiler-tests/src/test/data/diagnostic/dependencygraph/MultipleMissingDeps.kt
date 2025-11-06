// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Specifically a regression to make sure we don't duplicate "requestedAt" calls

class Foo1
class Foo2
class Foo3

@Inject
class InjectedThing(
  <!METRO_ERROR!>foo1: Foo1<!>,
  <!METRO_ERROR!>foo2: Foo2<!>,
  <!METRO_ERROR!>foo3: Foo3<!>,
)

@DependencyGraph
interface AppGraph {
  val injectedThing: InjectedThing
}
