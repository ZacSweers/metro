@HasMemberInjections
abstract class Parent {
  @Inject
  var int: Int = 0
}

@HasMemberInjections
abstract class Child : Parent()

class GrandChild : Child()

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  fun inject(child: GrandChild)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val grandChild = GrandChild()
  graph.inject(grandChild)
  assertEquals(3, grandChild.int)
  return "OK"
}