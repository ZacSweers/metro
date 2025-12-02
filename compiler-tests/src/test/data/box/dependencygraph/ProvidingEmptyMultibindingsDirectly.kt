// https://github.com/ZacSweers/metro/issues/1069
@Inject class MyIntUser(val ints: Set<Int>, val intsMap: Map<String, Int>)

@DependencyGraph
interface AppGraph {
  val user: MyIntUser

  @Provides fun provideSomeMap(): Map<String, Int> = emptyMap()

  @Provides fun provideInts(): Set<Int> = emptySet()
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val user = graph.user
  assertTrue(user.ints.isEmpty())
  assertTrue(user.intsMap.isEmpty())
  return "OK"
}
