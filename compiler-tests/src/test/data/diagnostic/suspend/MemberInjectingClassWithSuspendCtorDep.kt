// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A constructor-injected class with @Inject members whose suspend-ness comes from a CONSTRUCTOR
// dependency (not a member): still an error. Suspend construction routes through nested suspend
// factories, which do not perform member injection, so this would silently skip `injectBar`.

@Inject class Bar

@Inject
class Foo(val dep: String) {
  @Inject fun injectBar(<!METRO_ERROR!>bar: Bar<!>) = Unit
}

@DependencyGraph
interface ExampleGraph {
  suspend fun foo(): Foo

  @Provides suspend fun provideString(): String = "db"
}
