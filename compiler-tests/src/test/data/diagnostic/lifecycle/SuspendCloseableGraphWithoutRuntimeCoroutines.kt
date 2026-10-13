// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!MISSING_RUNTIME_COROUTINES!>AppGraph<!> : SuspendCloseableGraph {
  @OnGraphClose suspend fun stop() {}
}
