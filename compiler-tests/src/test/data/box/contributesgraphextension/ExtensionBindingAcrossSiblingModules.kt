// MODULE: scope
// FILE: Source1.kt

abstract class LoggedInScope

// MODULE: api
// FILE: Source2.kt

interface ContributedInterface

// MODULE: extension(scope, api)
// FILE: Source3.kt

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val contributedInterface: ContributedInterface

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

// MODULE: contribution(scope, api)
// FILE: Source4.kt

@ContributesBinding(LoggedInScope::class)
@Inject
class Impl : ContributedInterface

// MODULE: main(scope, extension, api, contribution)
// FILE: Source5.kt

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph

fun box(): String {
  val child = createGraph<ExampleGraph>().createLoggedInGraph()
  assertIs<Impl>(child.contributedInterface)
  return "OK"
}
