@DefaultBinding<Base>
interface Base

interface Other

@ContributesBinding(AppScope::class)
@Inject
class Impl : Base, Other

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val base: Base
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val base = graph.base
  assertNotNull(base)
  assertEquals("Impl", base::class.simpleName)
  return "OK"
}
