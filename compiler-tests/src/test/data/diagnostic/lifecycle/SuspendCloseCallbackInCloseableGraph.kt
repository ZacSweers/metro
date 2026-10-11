// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_SUSPEND_PROVIDERS

@DependencyGraph
interface AppGraph : CloseableGraph {
  @OnGraphClose suspend fun <!SUSPEND_CLOSE_REQUIRED!>stop<!>() {}
}
