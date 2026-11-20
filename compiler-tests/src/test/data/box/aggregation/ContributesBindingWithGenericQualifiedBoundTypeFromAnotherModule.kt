// MODULE: lib
// FILE: Library.kt
package test

interface ContributedInterface<T>

@ContributesBinding(
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
  @Named("named") val contributedInterface: ContributedInterface<String>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(Impl::class, contributedInterface::class)
  return "OK"
}
