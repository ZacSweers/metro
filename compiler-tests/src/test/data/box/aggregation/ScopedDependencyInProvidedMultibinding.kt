interface ContributedInterface

@Inject
@ContributesIntoSet(AppScope::class)
class Impl(val singleton: Singleton) : ContributedInterface

@Inject @SingleIn(AppScope::class) class Singleton

@Inject class Wrapper(val provider: () -> Set<ContributedInterface>)

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val wrapper: Wrapper
  val singleton: Singleton
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val provider = graph.wrapper.provider
  repeat(2) {
    assertSame(graph.singleton, assertIs<Impl>(provider().first()).singleton)
  }
  return "OK"
}
