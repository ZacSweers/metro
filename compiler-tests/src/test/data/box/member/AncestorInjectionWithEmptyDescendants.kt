@HasMemberInjections
abstract class Parent {
  @Inject
  var int: Int = 0
}

@HasMemberInjections
abstract class Child : Parent()

@Inject
class GrandChild : Child()

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  val grandChild: GrandChild
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.grandChild.int)
  return "OK"
}