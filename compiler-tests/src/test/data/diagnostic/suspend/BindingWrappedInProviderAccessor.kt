// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// Synchronous provider and Lazy<T> accessors over a suspend binding can never await the work — they
// must error just like synchronous provider/lazy dependency edges do. Assisted-inject targets with
// synchronous-provider-wrapped suspend deps are the same hole via a different path.

@AssistedInject
class Creator(@Assisted val region: String, <!METRO_ERROR!>val db: () -> String<!>) {
  @AssistedFactory
  fun interface Factory {
    fun create(region: String): Creator
  }
}

@DependencyGraph
interface ExampleGraph {
  val <!METRO_ERROR!>value<!>: () -> String

  val <!METRO_ERROR!>lazyValue<!>: Lazy<String>

  val <!METRO_ERROR!>nestedProvider<!>: suspend () -> (() -> String)

  val <!METRO_ERROR!>nestedLazy<!>: SuspendLazy<Lazy<String>>

  val factory: Creator.Factory

  @Provides suspend fun provideString(): String = "hello"
}
