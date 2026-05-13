@DefaultBinding<Base>
interface Base

interface Other

@ContributesIntoSet(AppScope::class)
@Inject
class Impl1 : Base, Other

@ContributesIntoSet(AppScope::class)
@Inject
class Impl2 : Base, Other

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val bases: Set<Base>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val bases = graph.bases
  assertEquals(2, bases.size)
  return "OK"
}
