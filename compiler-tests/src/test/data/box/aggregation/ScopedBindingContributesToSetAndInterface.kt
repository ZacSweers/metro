@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedSet: Set<ContributedInterface>
  val contributedInterface: SecondInterface
}

interface ContributedInterface
interface SecondInterface

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding<SecondInterface>())
@ContributesIntoSet(AppScope::class, binding<ContributedInterface>())
@Inject class Impl : ContributedInterface, SecondInterface

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val element = assertIs<Impl>(graph.contributedSet.single())
  assertSame(graph.contributedInterface, element)
  return "OK"
}
