// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A suspend accessor on an @Includes-ed graph is a suspend binding in the consuming graph, so a
// non-suspend accessor over it must error just like any other suspend binding.

@DependencyGraph
interface DatabaseGraph {
  suspend fun database(): String

  @Provides suspend fun provideDatabase(): String = "db"
}

@DependencyGraph
interface AppGraph {
  val <!SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR!>database<!>: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes databaseGraph: DatabaseGraph): AppGraph
  }
}
