// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesBinding

interface ContributedInterface

@ContributesBinding(AppScope::class, ignoreQualifier = true)
@Named("qualified")
object Impl : ContributedInterface

@ContributesGraphExtension(AppScope::class)
interface ExampleGraphExtension {
  val contributedInterface: ContributedInterface

  @ContributesGraphExtension.Factory(Unit::class)
  interface Factory {
    fun createExampleGraphExtension(): ExampleGraphExtension
  }
}

@DependencyGraph(Unit::class, isExtendable = true)
interface UnitGraph

fun box(): String {
  val graph = createGraph<UnitGraph>().createExampleGraphExtension()
  assertTrue(graph.contributedInterface == Impl)
  return "OK"
}