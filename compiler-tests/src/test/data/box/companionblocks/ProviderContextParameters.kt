// MIN_COMPILER_VERSION: 2.5.0-Beta1
// ENABLE_SUSPEND_PROVIDERS

class ContextValue(val value: String)

class CombinedContextValue(val value: String)

class DefaultSuffix(val value: String)

class DefaultContextValue(val value: String)

class SuspendedValue(val value: String)

class TransitiveContextValue(val value: String)

@BindingContainer
interface ContextProviders {
  companion {
    @Provides
    context(value: String)
    fun provideValue(): ContextValue = ContextValue(value)

    @Provides
    context(value: String)
    fun provideCombinedValue(length: Int): CombinedContextValue =
      CombinedContextValue("$value:$length")

    @Provides
    context(value: String)
    private fun provideDefaultValue(
      suffix: DefaultSuffix = DefaultSuffix(value)
    ): DefaultContextValue = DefaultContextValue(suffix.value)

    @Provides
    context(value: String)
    fun provideTransitiveValue(suspended: SuspendedValue): TransitiveContextValue =
      TransitiveContextValue("$value:${suspended.value}")

    @get:Provides
    context(value: String)
    val providedLength: Int
      get() = value.length
  }
}

@DependencyGraph(bindingContainers = [ContextProviders::class])
interface AppGraph {
  val value: ContextValue
  val combined: CombinedContextValue
  val defaultValue: DefaultContextValue
  val length: Int
  val deferred: suspend () -> TransitiveContextValue

  @Provides fun value(): String = "context"

  @Provides suspend fun suspendedValue(): SuspendedValue = SuspendedValue("suspend")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("context", graph.value.value)
  assertEquals("context:7", graph.combined.value)
  assertEquals("context", graph.defaultValue.value)
  assertEquals(7, graph.length)
  runBlocking { assertEquals("context:suspend", graph.deferred().value) }
  return "OK"
}
