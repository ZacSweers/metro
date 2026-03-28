// Verify that @ContributesIntoSet works with contribution providers.

interface Plugin {
  fun name(): String
}

@ContributesIntoSet(AppScope::class)
@Inject
class PluginA : Plugin {
  override fun name() = "A"
}

@ContributesIntoSet(AppScope::class)
@Inject
class PluginB : Plugin {
  override fun name() = "B"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val plugins: Set<Plugin>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val names = graph.plugins.map { it.name() }.sorted()
  assertEquals(listOf("A", "B"), names)
  return "OK"
}
