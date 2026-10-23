// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY

@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides suspend fun message(): String = "message"
  @OnGraphInit fun start(<!SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR!>message: String<!>) {}
}
