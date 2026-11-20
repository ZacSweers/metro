// MODULE: lib
// FILE: Library.kt
package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class)
@Inject
class Impl : ContributedInterface

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterfaces: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertTrue(contributedInterfaces.isNotEmpty())
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.first()::class)
  return "OK"
}
