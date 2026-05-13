// WITH_KI_ANVIL

import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

interface ContributedInterface

interface OtherInterface

// Intentional different parameter order
@ContributesBinding(AppScope::class, multibinding = true, boundType = ContributedInterface::class)
@Inject
class Impl : ContributedInterface, OtherInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedSet: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(1, graph.contributedSet.size)
  assertEquals("Impl", graph.contributedSet.single()::class.qualifiedName)
  return "OK"
}
