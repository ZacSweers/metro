// Verify that @ContributesIntoMap works with contribution providers.

// MODULE: common
interface Handler

// MODULE: lib1(common)
@ContributesIntoMap(AppScope::class)
@StringKey("auth")
@Inject
class AuthHandler : Handler

// MODULE: lib2(common)
@ContributesIntoMap(AppScope::class)
@StringKey("home")
@Inject
class HomeHandler : Handler

// MODULE: main(lib1, lib2, common)
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
