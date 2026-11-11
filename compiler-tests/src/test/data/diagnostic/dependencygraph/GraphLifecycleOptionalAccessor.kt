// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// METRO_JVM_ONLY

interface Foo
class DefaultFoo : Foo

// An optional accessor doesn't make a callback's required request for the same key optional.
@DependencyGraph
interface AppGraph : CloseableGraph {
  @OptionalBinding
  val foo: Foo
    get() = DefaultFoo()

  @OnGraphInit fun start(<!MISSING_BINDING!>foo: Foo<!>) {}
}
