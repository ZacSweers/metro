// Ensure member injections are ancestor-first order

private val callOrder = mutableListOf<String>()

@Inject class GrandparentDependency

@Inject class ParentDependency

@Inject class ChildDependency

@HasMemberInjections
open class Grandparent {
  @Inject
  fun initializeGrandparent(value: GrandparentDependency) {
    callOrder += "grandparent"
  }
}

@HasMemberInjections
open class Parent : Grandparent() {
  @Inject
  fun initializeParent(value: ParentDependency) {
    callOrder += "parent"
  }
}

@Inject
class Child : Parent() {
  @Inject
  fun initializeChild(value: ChildDependency) {
    callOrder += "child"
  }
}

@DependencyGraph
interface AppGraph {
  val child: Child
  val childInjector: MembersInjector<Child>

  fun inject(child: Child)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val expected = listOf("grandparent", "parent", "child")

  graph.childInjector.injectMembers(Child())
  assertEquals(expected, callOrder, "MembersInjector hierarchy order")
  callOrder.clear()

  graph.child
  assertEquals(expected, callOrder, "Constructor-generated hierarchy order")
  callOrder.clear()

  graph.inject(Child())
  assertEquals(expected, callOrder, "Direct graph injection hierarchy order")

  return "OK"
}
