// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  val value: String

  @Provides suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>provideValue<!>(): String = "value"
}

@DependencyGraph
interface AccessorGraph {
  suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>value<!>(): String

  @Provides fun provideValue(): String = "value"
}

@Inject
class NestedConsumer(
  val value: <!SUSPEND_PROVIDERS_NOT_ENABLED!>() -> suspend () -> String<!>
)

@DependencyGraph
interface NestedAccessorGraph {
  val value: <!SUSPEND_PROVIDERS_NOT_ENABLED!>() -> suspend () -> String<!>

  @Provides fun provideValue(): String = "value"
}
