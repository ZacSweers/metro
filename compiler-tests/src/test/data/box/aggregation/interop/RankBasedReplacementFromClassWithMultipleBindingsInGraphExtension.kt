// Similar to https://github.com/ZacSweers/metro/issues/1549 but for 'rank' processing
// The important conditions in this test case:
// - There is a class LowRankImpl with one or more bindings
// - There is another class HighRankImpl with the same binding as LowRankImpl, at least one other
// binding, and one of the bindings replaces LowRankImpl
// - The bindings are contributed to a graph extension

// WITH_ANVIL
// MODULE: lib
import com.squareup.anvil.annotations.ContributesBinding

interface ContributedInterface

interface OtherInterface

interface LoggedInScope

@ContributesBinding(LoggedInScope::class, boundType = ContributedInterface::class)
object LowRankImpl : ContributedInterface, OtherInterface

@ContributesBinding(LoggedInScope::class, boundType = OtherInterface::class)
@ContributesBinding(LoggedInScope::class, boundType = ContributedInterface::class, rank = 100)
object HighRankImpl : ContributedInterface, OtherInterface

// MODULE: main(lib)
@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val contributedInterface: ContributedInterface

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(AppScope::class) interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>().createLoggedInGraph()
  assertTrue(graph.contributedInterface == HighRankImpl)
  return "OK"
}
