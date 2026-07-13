// ENABLE_SUSPEND_PROVIDERS

// RENDER_DIAGNOSTICS_FULL_TEXT
@file:Suppress("OPT_IN_USAGE")
@DependencyGraph
interface ExampleGraph {
  val directLazy: <!UNSUPPORTED_SUSPEND_MAP_VALUE!>Map<String, SuspendLazy<Int>><!>

  val nestedLazy: <!UNSUPPORTED_SUSPEND_MAP_VALUE!>Map<String, () -> SuspendLazy<Int>><!>

  val nestedSuspendFunction: <!UNSUPPORTED_SUSPEND_MAP_VALUE!>Map<String, suspend () -> suspend () -> Int><!>

  val supported: Map<String, suspend () -> Int>

  @Provides @IntoMap @StringKey("value") suspend fun provideInt(): Int = 1
}
