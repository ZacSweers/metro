// Edge case of explicitly providing a constructor-injected assisted type
// overriding the one on the graph and thus bypassing the error reporting
// we do for doing this normally
@AssistedInject
class Example(@Assisted val input: String) {
  @AssistedFactory
  fun interface Factory {
    fun create(input: String): Example
  }
}

@DependencyGraph
interface AppGraph {
  // Request the assisted type
  @Named("qualified") val example: Example

  @Provides
  val string: String
    get() = "Hello"

  // Scenario 1: do your own assistance, bypass injection
  @Provides @Named("qualified") fun provideExample(input: String): Example = Example(input)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.example)
  return "OK"
}
