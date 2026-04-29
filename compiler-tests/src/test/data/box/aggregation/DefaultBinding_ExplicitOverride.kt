@DefaultBinding<Base>
interface Base

interface Other

// Explicit binding overrides the @DefaultBinding
@ContributesBinding(AppScope::class, binding = binding<Other>())
@Inject
class Impl : Base, Other

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val other: Other
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val other = graph.other
  assertNotNull(other)
  assertEquals("Impl", other::class.simpleName)
  return "OK"
}
