// Verify that @ContributesIntoMap works with contribution providers.

interface Handler

@ContributesIntoMap(AppScope::class)
@StringKey("auth")
@Inject
class AuthHandler : Handler

@ContributesIntoMap(AppScope::class)
@StringKey("home")
@Inject
class HomeHandler : Handler

@DependencyGraph(AppScope::class)
interface AppGraph {
  val handlers: Map<String, Handler>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("auth", "home"), graph.handlers.keys)
  assertEquals("AuthHandler", graph.handlers["auth"]!!::class.simpleName)
  assertEquals("HomeHandler", graph.handlers["home"]!!::class.simpleName)
  return "OK"
}
