interface ChildScope
interface Plugin {
  val value: String
}

@Inject
@ContributesIntoSet(AppScope::class)
class ParentPlugin : Plugin {
  override val value = "parent"
}

@Inject
@ContributesIntoSet(ChildScope::class)
class FirstChildPlugin : Plugin {
  override val value = "first child"
}

@Inject
@ContributesIntoSet(ChildScope::class)
class SecondChildPlugin : Plugin {
  override val value = "second child"
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val plugins: Set<Plugin>

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val plugins: Set<Plugin>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("parent"), graph.plugins.map { it.value }.toSet())
  assertEquals(
    setOf("parent", "first child", "second child"),
    graph.createChildGraph().plugins.map { it.value }.toSet(),
  )
  return "OK"
}
