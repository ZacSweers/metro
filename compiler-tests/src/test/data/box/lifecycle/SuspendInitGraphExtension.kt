// ENABLE_SUSPEND_PROVIDERS

// A graph extension whose init suspends is created through a suspend factory function. That works
// whether the parent exposes the factory or implements it.

val events = mutableListOf<String>()

@GraphExtension
interface UserGraph : CloseableGraph {
  @OnGraphInit
  suspend fun start(name: String) {
    events += "start $name"
  }

  @GraphExtension.Factory
  fun interface Factory {
    suspend fun create(@Provides name: String): UserGraph
  }
}

@DependencyGraph
interface AppGraph : CloseableGraph {
  val userGraphFactory: UserGraph.Factory
}

@DependencyGraph
interface ImplementingGraph : UserGraph.Factory, CloseableGraph

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  runBlocking { appGraph.userGraphFactory.create("exposed") }
  val implementingGraph = createGraph<ImplementingGraph>()
  runBlocking { implementingGraph.create("implemented") }
  assertEquals(listOf("start exposed", "start implemented"), events)
  return "OK"
}
