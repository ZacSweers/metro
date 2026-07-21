// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// Synchronous provider and Lazy<T> accessors over a suspend binding can never await the work — they
// must error just like synchronous provider/lazy dependency edges do. Assisted-inject targets with
// synchronous-provider-wrapped suspend deps are the same hole via a different path.

@AssistedInject
class Creator(@Assisted val region: String, <!SUSPEND_BINDING_WRAPPED_IN_PROVIDER!>val db: () -> String<!>) {
  @AssistedFactory
  fun interface Factory {
    fun create(region: String): Creator
  }
}

@DependencyGraph
interface ExampleGraph {
  val <!SUSPEND_BINDING_WRAPPED_IN_PROVIDER!>value<!>: () -> String

  val <!SUSPEND_BINDING_WRAPPED_IN_LAZY!>lazyValue<!>: Lazy<String>

  val <!SUSPEND_BINDING_WRAPPED_IN_PROVIDER!>nestedProvider<!>: suspend () -> (() -> String)

  val <!SUSPEND_BINDING_WRAPPED_IN_LAZY!>nestedLazy<!>: SuspendLazy<Lazy<String>>

  val factory: Creator.Factory

  @Provides suspend fun provideString(): String = "hello"
}
