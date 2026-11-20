// WITH_ANVIL

// Covers the use of Metro's [Qualifier] annotation and a Metro-defined qualifier
interface ContributedInterface

@Named("Bob")
@com.squareup.anvil.annotations.ContributesBinding(AppScope::class)
object Impl1 : ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface

  @Named("Bob")
  val namedContributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertSame(Impl2, graph.contributedInterface)
  assertSame(Impl1, graph.namedContributedInterface)
  return "OK"
}
