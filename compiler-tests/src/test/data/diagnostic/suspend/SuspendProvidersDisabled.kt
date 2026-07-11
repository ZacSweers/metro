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
