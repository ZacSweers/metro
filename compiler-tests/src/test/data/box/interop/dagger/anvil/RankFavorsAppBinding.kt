// WITH_ANVIL
// MODULE: lib
// FILE: Library.kt
interface ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 1)
object LibImpl : ContributedInterface

// MODULE: main(lib)
// FILE: Main.kt

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
object AppImpl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertSame(AppImpl, graph.contributedInterface)
  return "OK"
}
