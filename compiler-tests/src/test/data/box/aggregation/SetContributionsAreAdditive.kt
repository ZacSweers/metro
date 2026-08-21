interface Plugin

@Inject @ContributesIntoSet(AppScope::class) class FirstPlugin : Plugin
@Inject @ContributesIntoSet(AppScope::class) class SecondPlugin : Plugin

@DependencyGraph(AppScope::class)
interface AppGraph {
  val plugins: Set<Plugin>
}

fun box(): String {
  assertEquals(2, createGraph<AppGraph>().plugins.size)
  return "OK"
}
