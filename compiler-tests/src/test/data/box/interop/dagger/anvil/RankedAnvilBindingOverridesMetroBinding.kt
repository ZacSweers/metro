// WITH_ANVIL

// Covers a bug where the codebase has a Dagger-Anvil-ContributesBinding usage that outranks a
// Metro-ContributesBinding usage with an explicit type and multiple supertypes. Basically ensures
// that rank processing doesn't rely on the outranked binding using the Dagger-Anvil annotation.
interface ContributedInterface

interface OtherInterface

// Having two supertypes and an explicit binding type with Metro's @ContributesBinding
// annotation is the key piece of this repro
@ContributesBinding(AppScope::class, binding = binding<ContributedInterface>())
object Impl1 : ContributedInterface, OtherInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 10)
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertSame(Impl2, graph.contributedInterface)
  return "OK"
}
