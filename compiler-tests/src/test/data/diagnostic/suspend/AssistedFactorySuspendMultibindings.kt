// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Metro's eager collection aggregation can't await suspend contributions, even with a suspend create().
// Exposing only the factory checks that its target's constructor requests are still validated.

@AssistedInject
class Target(
  @Assisted val name: String,
  <!MULTIBINDING_OVER_SUSPEND_BINDINGS!>val values: Set<String><!>,
  <!MULTIBINDING_OVER_SUSPEND_BINDINGS!>val valuesByName: Map<String, String><!>,
) {
  @AssistedFactory
  interface Factory {
    suspend fun create(name: String): Target
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: Target.Factory

  @Provides @IntoSet suspend fun provideElement(): String = listOf("value").single()

  @Provides @IntoMap @StringKey("value")
  suspend fun provideMapValue(): String = listOf("value").single()
}
