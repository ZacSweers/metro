@HasMemberInjections
abstract class Parent {
  @Inject
  var int: Int = 0
}

@Inject
class Child : Parent()

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3
  val child: Child
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.child.int)
  return "OK"
}