// Verify that @MapKey annotations are not copied onto generated @Binds
// contributions when the binding is not @IntoMap (e.g. @ContributesBinding).
// Previously, if a class had both @ContributesBinding and @ContributesIntoMap
// with a map key, the key would leak into the non-map binding.
// https://github.com/ZacSweers/metro/pull/883

interface MyService

@ContributesBinding(AppScope::class)
@ContributesIntoMap(AppScope::class)
@StringKey("impl")
@Inject
class MyServiceImpl : MyService

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  // Regular binding should work without map key interference
  val service: MyService
  // Map binding should still get the key
  val serviceMap: Map<String, MyService>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertTrue(graph.service is MyServiceImpl)
  assertEquals(setOf("impl"), graph.serviceMap.keys)
  assertTrue(graph.serviceMap["impl"] is MyServiceImpl)
  return "OK"
}
