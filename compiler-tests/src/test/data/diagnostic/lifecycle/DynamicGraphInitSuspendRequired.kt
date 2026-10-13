// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// The replaced binding suspends, so this dynamic graph's init suspends even though the original
// graph's doesn't.

@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides fun provideMessage(): String = "real"

  @OnGraphInit fun log(<!LIFECYCLE_CALLBACK_SUSPEND_REQUIRED, UNSCOPED_LIFECYCLE_PARAMETER!>message: String<!>) {}
}

@BindingContainer
object FakeBindings {
  @Provides suspend fun provideMessage(): String = "fake"
}

fun createTestGraph() = <!GRAPH_INIT_SUSPEND_REQUIRED!>createDynamicGraph<AppGraph>(FakeBindings)<!>
