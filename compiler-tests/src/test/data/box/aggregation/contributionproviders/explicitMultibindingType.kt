interface Base {
  val string: String
}

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<@StringKey("KeyA") Base>())
@ContributesIntoMap(AppScope::class, binding = binding<@StringKey("KeyB") Base>())
class MapImpl : Base {
  override val string: String = "map"
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<@Named("SetA") Base>())
@ContributesIntoSet(AppScope::class, binding = binding<@Named("SetB") Base>())
class SetImpl : Base {
  override val string: String = "set"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<String, Base>
  @Named("SetA") val setA: Set<Base>
  @Named("SetB") val setB: Set<Base>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(2, graph.map.size)
  assertEquals(1, graph.setA.size)
  assertEquals(1, graph.setB.size)
  assertEquals("map", graph.map["KeyA"]!!.string)
  assertEquals("map", graph.map["KeyB"]!!.string)
  assertEquals("set", graph.setA.first().string)
  assertEquals("set", graph.setB.first().string)
  return "OK"
}