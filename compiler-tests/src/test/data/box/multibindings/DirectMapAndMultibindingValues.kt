@BindingContainer
object DirectValues {
  @Provides fun directMap(): Map<String, Int> = mapOf("direct" to 1)
}

@BindingContainer
object ContributedValues {
  @Provides @IntoMap @StringKey("contributed") fun contribution(): Int = 2
}

@DependencyGraph(bindingContainers = [DirectValues::class])
interface DirectGraph {
  val values: Map<String, Int>
}

// A plain map request here would be a MultibindingConflict. A provider-valued request can't use the
// plain map, so it isn't a conflict.
@DependencyGraph(bindingContainers = [DirectValues::class, ContributedValues::class])
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
