@DependencyGraph(Unit::class, excludes = [ContributedInterface::class])
interface ExampleGraph

@ContributesTo(Unit::class)
internal interface ContributedInterface

fun box(): String {
  assertFalse(createGraph<ExampleGraph>() is ContributedInterface)
  return "OK"
}
