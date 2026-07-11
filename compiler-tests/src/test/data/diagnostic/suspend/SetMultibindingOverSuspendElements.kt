// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Set multibindings aggregate scalar values eagerly, which requires awaiting each suspend
// element inside non-suspend aggregation code. This is unsupported and must be an error, even when
// accessed from a suspend accessor.

@DependencyGraph
interface ExampleGraph {
  suspend fun <!METRO_ERROR!>values<!>(): Set<String>

  @Provides @IntoSet suspend fun provideOne(): String = "one"

  @Provides @IntoSet fun provideTwo(): String = "two"
}
