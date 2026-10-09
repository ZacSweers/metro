// ENABLE_SUSPEND_PROVIDERS
// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18823579

@Inject class Handler(val names: Map<String, suspend () -> String>)

@DependencyGraph
interface AppGraph {
  val child: ChildGraph
  @Provides @IntoMap @StringKey("name") suspend fun name(): String = "parent"
}

@GraphExtension
interface ChildGraph {
  val handler: Handler
}

fun box(): String = runBlocking {
  val child = createGraph<AppGraph>().child
  val first = child.handler
  assertEquals("parent", first.names.getValue("name")())
  assertTrue(first !== child.handler)
  "OK"
}
