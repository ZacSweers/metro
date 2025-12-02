// https://github.com/ZacSweers/metro/issues/1069
@Inject class MyIntUser(val ints: Map<String, Provider<Int>>)

@DependencyGraph
interface AppGraph {
  val user: MyIntUser

  @Provides fun provideSomeMap(): Map<String, Provider<Int>> = emptyMap()
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val user = graph.user
  assertTrue(user.ints.isEmpty())
  return "OK"
}
