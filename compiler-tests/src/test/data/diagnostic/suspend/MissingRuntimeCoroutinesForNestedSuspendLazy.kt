// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
@file:Suppress("DESUGARED_PROVIDER_WARNING", "OPT_IN_USAGE")
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

// This container is not included in the graph above. Its source factory still needs to diagnose
// the missing runtime rather than compile an invoke() body that always throws.
@BindingContainer
object UnusedBindings {
  @Provides
  fun provideLength(
    <!MISSING_RUNTIME_COROUTINES!>value: Provider<SuspendLazy<String>><!>
  ): Int = 0
}
