// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// METRO_JVM_ONLY

interface Foo
class DefaultFoo : Foo

// A defaulted callback parameter doesn't take over an accessor's required request.
@DependencyGraph
interface AppGraph : CloseableGraph {
  val <!MISSING_BINDING!>foo<!>: Foo

  @OnGraphInit fun start(foo: Foo = DefaultFoo()) {}
}
