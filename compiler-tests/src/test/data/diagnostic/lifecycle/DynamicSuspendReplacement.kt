// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

@DependencyGraph(AppScope::class)
interface ExampleGraph : CloseableGraph {
  @Provides @SingleIn(AppScope::class) fun value(): String = "default"
  @OnGraphInit fun initialize(<!SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR!>value: String<!>) {}
}

@BindingContainer
object Replacement {
  @Provides <!INLINABLE_PROVIDES_WARNING!>@SingleIn(AppScope::class)<!> suspend fun value(): String = "replacement"
}

fun create() = createDynamicGraph<ExampleGraph>(Replacement)
