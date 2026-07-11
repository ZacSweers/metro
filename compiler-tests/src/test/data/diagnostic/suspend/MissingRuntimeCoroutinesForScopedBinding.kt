// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

abstract class AppScope private constructor()

@DependencyGraph(scope = AppScope::class)
interface <!MISSING_RUNTIME_COROUTINES!>ExampleGraph<!> {
  suspend fun value(): String

  @Provides @SingleIn(AppScope::class) suspend fun provideValue(): String = "value"
}
