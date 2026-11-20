// MODULE: lib
// FILE: Library.kt
package test

interface ContributedInterface<T>

@ContributesIntoSet(
  AppScope::class,
  binding<@Named("named") ContributedInterface<String>>()
)
@Inject
class Impl : ContributedInterface<String>

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("named") val contributedInterfaces: Set<ContributedInterface<String>>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterfaces = graph.contributedInterfaces
  assertNotNull(contributedInterfaces)
  assertEquals(1, contributedInterfaces.size)
  assertEquals(Impl::class, contributedInterfaces.first()::class)
  return "OK"
}
