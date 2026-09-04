@BindingContainer
object Values {
  @Provides fun directMap(): Map<String, Int> = mapOf("direct" to 1)

  @Provides @IntoMap @StringKey("contributed") fun contribution(): Int = 2
}

@DependencyGraph(bindingContainers = [Values::class])
interface DirectGraph {
  val values: Map<String, Int>
}

@DependencyGraph(bindingContainers = [Values::class])
interface WrappedGraph {
  val values: Map<String, () -> Int>
}

fun box(): String {
  assertEquals(mapOf("direct" to 1), createGraph<DirectGraph>().values)
  // The plain map cannot supply provider values, so lookup must reach the contributions.
  assertEquals(
    mapOf("contributed" to 2),
    createGraph<WrappedGraph>().values.mapValues { it.value() },
  )
  return "OK"
}
