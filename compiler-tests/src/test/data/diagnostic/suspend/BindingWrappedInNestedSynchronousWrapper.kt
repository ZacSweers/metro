// ENABLE_SUSPEND_PROVIDERS

// RENDER_DIAGNOSTICS_FULL_TEXT
@file:Suppress("DESUGARED_PROVIDER_WARNING")
@OptIn(ExperimentalMetroCoroutinesApi::class)
@DependencyGraph
interface ExampleGraph {
  suspend fun value(): Long

  val providerValue: suspend () -> <!SYNCHRONOUS_WRAPPER_INSIDE_SUSPEND_WRAPPER!>Provider<String><!>

  val suspendLazyProviderValue: SuspendLazy<<!SYNCHRONOUS_WRAPPER_INSIDE_SUSPEND_WRAPPER!>Provider<String><!>>

  val lazyValue: suspend () -> <!SYNCHRONOUS_WRAPPER_INSIDE_SUSPEND_WRAPPER!>Lazy<Int><!>

  val functionValue: suspend () -> <!SYNCHRONOUS_WRAPPER_INSIDE_SUSPEND_WRAPPER!>() -> String<!>

  val deepValue: Provider<SuspendLazy<<!SYNCHRONOUS_WRAPPER_INSIDE_SUSPEND_WRAPPER!>Provider<String><!>>>

  @Provides
  fun provideLong(
    provider: suspend () -> <!SYNCHRONOUS_WRAPPER_INSIDE_SUSPEND_WRAPPER!>Provider<String><!>,
    lazy: suspend () -> <!SYNCHRONOUS_WRAPPER_INSIDE_SUSPEND_WRAPPER!>Lazy<Int><!>,
  ): Long = 1L

  @Provides fun provideString(): String = "value"

  @Provides fun provideInt(): Int = 1
}
