// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
@file:Suppress("OPT_IN_USAGE")

// Provider<T> and Lazy<T> accessors over a suspend binding can never await the work — they must
// error just like Provider/Lazy dependency edges do. Assisted-inject targets with Provider-wrapped
// suspend deps are the same hole via a different path.

class Creator
<!SUGGEST_CLASS_INJECTION!>@AssistedInject<!>
constructor(@Assisted val region: String, <!METRO_ERROR!>val db: <!DESUGARED_PROVIDER_WARNING!>Provider<String><!><!>) {
  @AssistedFactory
  fun interface Factory {
    fun create(region: String): Creator
  }
}

@DependencyGraph
interface ExampleGraph {
  val <!METRO_ERROR!>value<!>: <!DESUGARED_PROVIDER_WARNING!>Provider<String><!>

  val <!METRO_ERROR!>lazyValue<!>: Lazy<String>

  val <!METRO_ERROR!>nestedProvider<!>: <!DESUGARED_PROVIDER_WARNING!>suspend () -> Provider<String><!>

  val <!METRO_ERROR!>nestedLazy<!>: SuspendLazy<Lazy<String>>

  val factory: Creator.Factory

  @Provides suspend fun provideString(): String = "hello"
}
