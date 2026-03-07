// Similar to https://github.com/ZacSweers/metro/issues/1549 but for 'rank' processing in root
// graphs
// The important conditions in this test case:
// - There is a class LowRankImpl with one or more bindings
// - There is another class HighRankImpl with the same binding as LowRankImpl, at least one other
// binding, and one of the bindings replaces LowRankImpl
// - The bindings are contributed to the root graph

// WITH_ANVIL
// MODULE: lib
import com.squareup.anvil.annotations.ContributesBinding

interface ContributedInterface

interface OtherInterface

@ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
object LowRankImpl : ContributedInterface, OtherInterface

@ContributesBinding(AppScope::class, boundType = OtherInterface::class)
@ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
object HighRankImpl : ContributedInterface, OtherInterface

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.contributedInterface == HighRankImpl)
  return "OK"
}
