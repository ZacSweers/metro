// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// FILE: lib.kt
@Inject class Bar

@Inject
class Foo(val dep: String) {
  @Inject fun injectBar(bar: Bar) = Unit
}

// MODULE: main(lib)
// FILE: main.kt
@DependencyGraph
interface <!METRO_ERROR!>ExampleGraph<!> {
  suspend fun foo(): Foo

  @Provides suspend fun provideString(): String = "db"
}
