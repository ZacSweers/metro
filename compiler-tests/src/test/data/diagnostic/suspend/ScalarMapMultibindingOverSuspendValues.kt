// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A scalar Map<K, V> over suspend values requires awaiting each value inside non-suspend
// aggregation code. Not supported — consumers must use Map<K, suspend () -> V> instead, which
// defers each value's resolution.

@DependencyGraph
interface ExampleGraph {
  suspend fun <!METRO_ERROR!>values<!>(): Map<String, Int>

  // This form is fine — values are deferred
  val deferredValues: Map<String, suspend () -> Int>

  @Provides @IntoMap @StringKey("one") suspend fun provideOne(): Int = 1

  @Provides @IntoMap @StringKey("two") fun provideTwo(): Int = 2
}
