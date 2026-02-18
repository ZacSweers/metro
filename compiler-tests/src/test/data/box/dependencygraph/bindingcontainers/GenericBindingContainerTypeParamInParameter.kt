// Generic binding container where type param is used in a parameter, not return type
@BindingContainer
class FormatterBindings<T>(private val value: T) {
  @Provides
  fun provideFormatted(tag: Int): String = "$tag: $value"
}

@BindingContainer
object TagBindings {
  @Provides fun provideTag(): Int = 99
}

@DependencyGraph(bindingContainers = [TagBindings::class])
interface AppGraph {
  val formatted: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: FormatterBindings<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(FormatterBindings(42))
  assertEquals("99: 42", graph.formatted)
  return "OK"
}
