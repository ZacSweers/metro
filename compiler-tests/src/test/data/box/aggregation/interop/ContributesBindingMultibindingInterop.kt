// WITH_KI_ANVIL

import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

interface ContributedInterface

@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class Impl1 : ContributedInterface

@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedSet: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(2, graph.contributedSet.size)
  return "OK"
}
