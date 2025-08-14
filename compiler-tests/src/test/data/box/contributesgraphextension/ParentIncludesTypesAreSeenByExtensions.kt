@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes serviceProvider: IntProvider): AppGraph
  }
}

@GraphExtension(scope = Unit::class)
interface ChildGraph {
  @GraphExtension.Factory @ContributesTo(scope = AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

interface IntProvider {
  fun int(): Int
}

fun box(): String {
  val graph =
    createGraphFactory<AppGraph.Factory>()
      .create(
        object : IntProvider {
          override fun int(): Int {
            return 3
          }
        }
      )
  val child = graph.create()
  assertNotNull(child)
  return "OK"
}
