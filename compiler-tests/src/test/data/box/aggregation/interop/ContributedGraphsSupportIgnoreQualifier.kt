// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesBinding

interface ContributedInterface

@ContributesBinding(AppScope::class, ignoreQualifier = true)
@Named("qualified")
object Impl : ContributedInterface

@GraphExtension(AppScope::class)
interface ExampleGraphExtension {
  val contributedInterface: ContributedInterface

  @GraphExtension.Factory @ContributesTo(Unit::class)
  interface Factory {
    fun createExampleGraphExtension(): ExampleGraphExtension
  }
}

@DependencyGraph(Unit::class)
interface UnitGraph

fun box(): String {
  val graph = createGraph<UnitGraph>().createExampleGraphExtension()
  assertTrue(graph.contributedInterface == Impl)
  return "OK"
}