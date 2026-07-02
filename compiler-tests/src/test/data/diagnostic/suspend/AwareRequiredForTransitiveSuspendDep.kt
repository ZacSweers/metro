// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// IR-time check: an `@Inject` class with an unwrapped param that turns out to be bound to a
// suspend `@Provides` in the graph needs `@SuspendAware`. The FIR-level checker can't see the
// graph context (the param's source type is just `String`), but the IR-level Step 5 catches it
// once the suspend chain is resolved.

@Inject
class <!METRO_ERROR!>AccountCreator<!>(val database: String)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"
}
