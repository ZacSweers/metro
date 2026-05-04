interface MapHolder {
  fun strings(): Map<String, () -> String>
}

@DependencyGraph
interface AppGraph {
  val strings: Map<String, () -> String>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes holder: MapHolder): AppGraph
  }
}

fun box(): String {
  val strings = mapOf<String, () -> String>("1" to { "2" })
  val holder =
    object : MapHolder {
      override fun strings(): Map<String, () -> String> {
        return strings
      }
    }
  val graph = createGraphFactory<AppGraph.Factory>().create(holder)
  assertEquals(strings.mapValues { it.value() }, graph.strings.mapValues { it.value() })
  return "OK"
}
