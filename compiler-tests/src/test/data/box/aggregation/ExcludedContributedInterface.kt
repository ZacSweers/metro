@ContributesTo(AppScope::class)
interface ContributedInterface

@DependencyGraph(scope = AppScope::class, excludes = [ContributedInterface::class])
interface ExampleGraph

fun box(): String {
  assertFalse(createGraph<ExampleGraph>() is ContributedInterface)
  return "OK"
}
