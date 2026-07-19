// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// ENABLE_SUSPEND_PROVIDERS

// FILE: upstream.kt
class UpstreamValue(val provider: suspend () -> String)

@Inject
class UpstreamConsumer(val provider: suspend () -> String)

@Inject
class UpstreamFunctionConsumer(val provider: suspend () -> String)

@BindingContainer
object UpstreamBindings {
  @Provides
  fun provideValue(provider: suspend () -> String): UpstreamValue = UpstreamValue(provider)
}

// MODULE: main(lib)

// FILE: main.kt
@DependencyGraph
interface <!METRO_ERROR!>DownstreamFunctionGraph<!> {
  val functionConsumer: UpstreamFunctionConsumer

  @Provides fun provideString(): String = "value"
}

@DependencyGraph(bindingContainers = [UpstreamBindings::class])
interface <!METRO_ERROR!>DownstreamProviderGraph<!> {
  val consumer: UpstreamConsumer

  val value: UpstreamValue

  @Provides fun provideString(): String = "value"
}
