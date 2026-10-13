// ENABLE_SUSPEND_PROVIDERS

// Dynamic graphs check their own init against the factory used to create them, since replaced
// bindings can make init suspend.

val events = mutableListOf<String>()

@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides fun provideMessage(): String = "real"

  @OnGraphInit
  suspend fun start(message: String) {
    events += message
  }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(): AppGraph
  }
}

@BindingContainer
object FakeBindings {
  @Provides fun provideMessage(): String = "fake"
}

fun box(): String {
  val factory = createDynamicGraphFactory<AppGraph.Factory>(FakeBindings)
  runBlocking { factory.create() }
  assertEquals(listOf("fake"), events)
  return "OK"
}
