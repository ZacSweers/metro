// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph(scope = AppScope::class)
interface <!MISSING_RUNTIME_COROUTINES!>ExampleGraph<!> {
  suspend fun value(): String

  @Provides @SingleIn(AppScope::class) suspend fun provideValue(): String = "value"
}
