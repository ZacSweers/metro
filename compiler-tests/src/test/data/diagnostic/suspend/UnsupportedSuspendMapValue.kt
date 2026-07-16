// ENABLE_SUSPEND_PROVIDERS

// RENDER_DIAGNOSTICS_FULL_TEXT
@file:Suppress("OPT_IN_USAGE")
@DependencyGraph
interface ExampleGraph {
  val directLazy: Map<String, <!UNSUPPORTED_SUSPEND_MAP_VALUE!>SuspendLazy<Int><!>>

  val nestedLazy: Map<String, <!UNSUPPORTED_SUSPEND_MAP_VALUE!>() -> SuspendLazy<Int><!>>

  val nestedSuspendFunction: Map<String, <!UNSUPPORTED_SUSPEND_MAP_VALUE!>suspend () -> suspend () -> Int<!>>

  val supported: Map<String, suspend () -> Int>

  @Provides @IntoMap @StringKey("value") suspend fun provideInt(): Int = 1
}
