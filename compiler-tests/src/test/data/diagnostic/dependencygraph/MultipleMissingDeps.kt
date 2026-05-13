// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Specifically a regression to make sure we don't duplicate "requestedAt" calls

class Foo1
class Foo2
class Foo3

@Inject
class InjectedThing(
  <!MISSING_BINDING!>foo1: Foo1<!>,
  <!MISSING_BINDING!>foo2: Foo2<!>,
  <!MISSING_BINDING!>foo3: Foo3<!>,
)

@DependencyGraph
interface AppGraph {
  val injectedThing: InjectedThing
}
