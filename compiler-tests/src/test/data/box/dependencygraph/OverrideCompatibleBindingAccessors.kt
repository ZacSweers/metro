@DependencyGraph
interface ExampleGraph {
  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes serviceProvider: ServiceProvider): ExampleGraph
  }
}

@ContributesTo(AppScope::class)
interface ServiceProvider : ServiceProvider1, ServiceProvider2

interface ServiceProvider1 {
  val string: String
  val int: Int
}

interface ServiceProvider2 {
  val int: Int
  val long: Long
}

fun box(): String {
  val graphFactory = createGraphFactory<ExampleGraph.Factory>()
  val serviceProvider = object : ServiceProvider {
    override val int: Int get() = 3
    override val string: String get() = "123"
    override val long: Long get() = 1L
  }
  val graph = graphFactory.create(serviceProvider)
  assertEquals(graph.int, 3)
  return "OK"
}