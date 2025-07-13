@DependencyGraph(bindingContainers = [StringBindings::class])
interface AppGraph {
  val string: String
}

// Simple self-referencing container
@BindingContainer(includes = [StringBindings::class])
class StringBindings {
  @Provides
  fun provideString(): String {
    return "string value"
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("string value", graph.string)
  return "OK"
}
