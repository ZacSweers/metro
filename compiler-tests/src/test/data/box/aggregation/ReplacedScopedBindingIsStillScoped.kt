// GENERATE_CONTRIBUTION_PROVIDERS: false
// This case exercises the binding mirror model used by the legacy test.

package test

interface ContributedInterface

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class Impl1 : ContributedInterface

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [Impl1::class])
class Impl2(
  val impl1: Impl1
) : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
  val impl1: Impl1
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val impl2 = assertIs<Impl2>(graph.contributedInterface)
  assertSame(graph.contributedInterface, impl2)
  val impl1 = impl2.impl1
  assertSame(graph.impl1, impl1)
  return "OK"
}
