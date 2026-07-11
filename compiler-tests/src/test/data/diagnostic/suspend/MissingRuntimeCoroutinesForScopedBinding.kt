// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

abstract class AppScope private constructor()

@DependencyGraph(scope = AppScope::class)
interface <!METRO_ERROR!>ExampleGraph<!> {
  suspend fun value(): String

  @Provides @SingleIn(AppScope::class) suspend fun provideValue(): String = "value"
}
