// MIN_COMPILER_VERSION: 2.5.0-Beta1
// LANGUAGE: +ContextParameters

class ContextValue(val value: String)

@BindingContainer
interface ContextProviders {
  companion {
    @Provides
    context(value: String)
    fun provideValue(): ContextValue = ContextValue(value)

    @get:Provides
    context(value: String)
    val providedLength: Int
      get() = value.length
  }
}

@DependencyGraph(bindingContainers = [ContextProviders::class])
interface AppGraph {
  val value: ContextValue
  val length: Int

  @Provides fun value(): String = "context"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("context", graph.value.value)
  assertEquals(7, graph.length)
  return "OK"
}
