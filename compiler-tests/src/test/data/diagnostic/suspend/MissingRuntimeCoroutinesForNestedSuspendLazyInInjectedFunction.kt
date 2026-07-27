// ENABLE_SUSPEND_PROVIDERS
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
// WITHOUT_RUNTIME_COROUTINES

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
@Inject
fun NestedLazyFunction(
  value: () -> SuspendLazy<String>
): String = value.toString()

@DependencyGraph
interface <!MISSING_RUNTIME_COROUTINES!>ExampleGraph<!> {
  val function: NestedLazyFunction

  @Provides fun provideValue(): String = "value"
}
