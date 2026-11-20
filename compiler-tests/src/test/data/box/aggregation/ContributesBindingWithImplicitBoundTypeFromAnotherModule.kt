// MODULE: lib
// FILE: Library.kt
package test

interface ContributedInterface

@ContributesBinding(AppScope::class)
@Inject
class Impl : ContributedInterface

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(Impl::class, contributedInterface::class)
  return "OK"
}
