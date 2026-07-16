// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
@file:Suppress("DESUGARED_PROVIDER_WARNING")
@OptIn(ExperimentalMetroCoroutinesApi::class)
@Inject
class <!MISSING_RUNTIME_COROUTINES!>NestedLazyConsumer<!>(
  val value: Provider<SuspendLazy<String>>
)

@DependencyGraph
interface <!MISSING_RUNTIME_COROUTINES!>ExampleGraph<!> {
  val consumer: NestedLazyConsumer

  @Provides fun provideValue(): String = "value"
}
