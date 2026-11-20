// WITH_ANVIL
// RUN_PIPELINE_TILL: BACKEND

interface ContributedInterface
interface ContributedInterface2

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100, boundType = ContributedInterface::class)
object Impl1 : ContributedInterface, ContributedInterface2

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}
