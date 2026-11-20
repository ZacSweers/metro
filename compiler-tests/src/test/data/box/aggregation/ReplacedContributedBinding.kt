interface ContributedInterface

@ContributesBinding(AppScope::class)
object Impl1 : ContributedInterface

@ContributesBinding(AppScope::class, replaces = [Impl1::class])
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  assertSame(Impl2, createGraph<ExampleGraph>().contributedInterface)
  return "OK"
}
