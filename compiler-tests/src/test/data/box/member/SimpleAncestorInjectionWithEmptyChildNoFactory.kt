@HasMemberInjections
abstract class Parent {
  @Inject
  var int: Int = 0
}

class Child : Parent()

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  fun inject(child: Child)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val child = Child()
  graph.inject(child)
  assertEquals(3, child.int)
  return "OK"
}