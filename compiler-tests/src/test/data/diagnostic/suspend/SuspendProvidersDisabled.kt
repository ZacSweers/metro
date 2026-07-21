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

@Inject
class CanonicalSuspendConsumer(
  // A suspend function nested in a canonical type is not a wrapper, so it isn't gated.
  val list: List<suspend () -> Unit>,
  val nestedMap: Map<String, List<suspend () -> Unit>>,
  // A suspend function in map-value position is a real suspend spelling and stays gated.
  val map: <!SUSPEND_PROVIDERS_NOT_ENABLED!>Map<String, suspend () -> String><!>,
)
