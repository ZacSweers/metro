// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// A class with BOTH a deferred suspend dep (legal, does not propagate) and an unwrapped
// transitively-suspend dep (propagates): the class is suspend because of the unwrapped edge only,
// and the error trace must walk through that edge. A sibling consumer deferring the same class
// stays legal.

class Config

@Inject class Database(val config: Config)

@Inject
class <!MISSING_RUNTIME_COROUTINES!>Worker<!>(
  // Deferred — legal for a non-suspend consumer, does not make Worker suspend by itself
  val lazyConfig: SuspendLazy<Config>,
  // Unwrapped dep on a transitively suspend binding — THIS makes Worker suspend
  val database: Database,
)

@Inject class Dashboard(val worker: suspend () -> Worker)

@DependencyGraph
interface <!MISSING_RUNTIME_COROUTINES!>ExampleGraph<!> {
  val <!SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR!>worker<!>: Worker

  // Deferring the same suspend class is fine
  val dashboard: Dashboard

  @Provides suspend fun provideConfig(): Config = Config()
}
