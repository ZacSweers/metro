interface MapHolder {
  fun strings(): Map<String, Provider<String>>
}

@DependencyGraph
interface AppGraph {
  val strings: Map<String, Provider<String>>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes holder: MapHolder): AppGraph
  }
}

fun box(): String {
  val strings = mapOf("1" to providerOf("2"))
  val holder =
    object : MapHolder {
      override fun strings(): Map<String, Provider<String>> {
        return strings
      }
    }
  val graph = createGraphFactory<AppGraph.Factory>().create(holder)
  assertEquals(strings.mapValues { it.value() }, graph.strings.mapValues { it.value() })
  return "OK"
}
