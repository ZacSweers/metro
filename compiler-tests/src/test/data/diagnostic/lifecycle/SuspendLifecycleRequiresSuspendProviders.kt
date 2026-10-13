// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!SUSPEND_PROVIDERS_NOT_ENABLED!>AppGraph<!> : SuspendCloseableGraph {
  @OnGraphInit suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>start<!>() {}

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>create<!>(): AppGraph
  }
}
