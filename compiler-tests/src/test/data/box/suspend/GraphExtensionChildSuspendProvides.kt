// ENABLE_SUSPEND_PROVIDERS

// A suspend @Provides owned by a graph extension (child), one of which depends on a non-suspend
// binding owned by the parent. Accessed from the child through suspend accessors.

@DependencyGraph
interface ParentGraph {
  fun childFactory(): ChildGraph.Factory

  @Provides fun provideBaseUrl(): String = "https://example.com"
}

@Inject class Service(val endpoint: Int)

@GraphExtension
interface ChildGraph {
  suspend fun endpoint(): Int

  suspend fun service(): Service

  // Child-owned suspend @Provides depending on the parent's non-suspend String.
  @Provides suspend fun provideEndpoint(baseUrl: String): Int = baseUrl.length

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childFactory().create()
  return runBlocking {
    assertEquals(19, child.endpoint())
    assertEquals(19, child.service().endpoint)
    "OK"
  }
}
