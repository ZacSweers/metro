// WITH_KI_ANVIL

import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

interface ContributedInterface

// Regular binding — provides a direct ContributedInterface binding
// Multibinding — also contributes into Set<ContributedInterface>
@ContributesBinding(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributed: ContributedInterface
  val contributedSet: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("Impl", graph.contributed::class.qualifiedName)
  assertEquals(1, graph.contributedSet.size)
  assertEquals("Impl", graph.contributedSet.single()::class.qualifiedName)
  return "OK"
}
