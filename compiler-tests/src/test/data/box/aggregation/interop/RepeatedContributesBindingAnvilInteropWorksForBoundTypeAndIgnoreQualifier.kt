// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesBinding

interface ContributedInterface
interface SecondInterface

@SingleIn(AppScope::class)
@ForScope(AppScope::class)
@ContributesBinding(AppScope::class, boundType = SecondInterface::class)
@ContributesBinding(AppScope::class, boundType = ContributedInterface::class, ignoreQualifier = true)
@Inject
class Impl : ContributedInterface, SecondInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
  @ForScope(AppScope::class) val secondInterface: SecondInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.qualifiedName, "Impl")
  val secondInterface = graph.secondInterface
  assertEquals<Any>(contributedInterface, secondInterface)
  return "OK"
}
