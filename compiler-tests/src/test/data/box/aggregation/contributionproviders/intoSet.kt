// Verify that @ContributesIntoSet works with contribution providers.

// MODULE: common
interface Plugin {
  fun name(): String
}

// MODULE: lib1(common)
@ContributesIntoSet(AppScope::class)
@Inject
class PluginA : Plugin {
  override fun name() = "A"
}

// MODULE: lib2(common)
@ContributesIntoSet(AppScope::class)
@Inject
class PluginB : Plugin {
  override fun name() = "B"
}

// MODULE: main(lib1, lib2, common)
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
