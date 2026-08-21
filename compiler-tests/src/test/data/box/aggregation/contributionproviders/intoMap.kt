// Verify that @ContributesIntoMap works with contribution providers and that priority replaces
// only a matching map entry.

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

@ContributesIntoMap(AppScope::class, priority = 100)
@StringKey("auth")
@Inject
class PreferredAuthHandler : Handler

// MODULE: main(lib1, lib2, common)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val handlers: Map<String, Handler>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("auth", "home"), graph.handlers.keys)
  assertEquals("PreferredAuthHandler", graph.handlers["auth"]!!::class.simpleName)
  assertEquals("HomeHandler", graph.handlers["home"]!!::class.simpleName)
  return "OK"
}
