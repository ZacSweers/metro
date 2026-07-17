// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Foo's constructor takes an unwrapped suspend String dep, so Foo is transitively suspend.
// A non-suspend accessor returning Foo therefore can't satisfy the suspend chain — it must
// either be `suspend fun foo(): Foo`, return `suspend () -> Foo`,
// or Foo's constructor must wrap the dep as `suspend () -> String`.

@Inject class Foo(val dep: String)

@DependencyGraph
interface ExampleGraph {
  val <!METRO_ERROR!>foo<!>: Foo

  @Provides suspend fun provideString(): String = "hello"
}
