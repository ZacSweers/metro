// MIN_COMPILER_VERSION: 2.5.0-Beta1
// GENERATE_CLASSES_IN_IR: false
// OMIT_REDUNDANT_MIRRORS: false

// MODULE: lib
// COMPANION_BLOCKS: false
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Library.kt

@Inject
class GenericValue<T> private constructor(@Named("value") val value: T) {
  @Inject lateinit var values: List<T>
}

@AssistedInject
class AssistedValue private constructor(@Assisted val id: Int, @Named("value") val value: String)

@AssistedFactory
interface AssistedValueFactory {
  fun create(id: Int): AssistedValue
}

@BindingContainer
class LibraryBindings {
  companion object {
    @Provides
    @Named("value")
    fun value(): String = "companion producer"

    @get:Provides
    val values: List<String>
      get() = listOf("companion producer")
  }
}

@DependencyGraph
interface LibraryGraph {
  val value: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides value: String): LibraryGraph
  }
}

// MODULE: main(lib)
// COMPANION_BLOCKS: true
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt

@DependencyGraph(bindingContainers = [LibraryBindings::class])
interface AppGraph {
  val generic: GenericValue<String>
  val factory: AssistedValueFactory
  val injector: MembersInjector<GenericValue<String>>
}

fun box(): String {
  val graph = AppGraph()
  assertEquals("companion producer", graph.generic.value)
  assertEquals(listOf("companion producer"), graph.generic.values)
  graph.generic.values = emptyList()
  graph.injector.injectMembers(graph.generic)
  assertEquals(listOf("companion producer"), graph.generic.values)
  val assisted = graph.factory.create(7)
  assertEquals(7, assisted.id)
  assertEquals("companion producer", assisted.value)
  assertEquals("legacy call", LibraryGraph.Companion.create("legacy call").value)
  assertEquals("factory value", createGraphFactory<LibraryGraph.Factory>().create("factory value").value)
  return "OK"
}
