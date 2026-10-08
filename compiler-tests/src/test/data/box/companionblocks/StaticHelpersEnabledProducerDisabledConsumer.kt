// MIN_COMPILER_VERSION: 2.5.0-Beta1
// GENERATE_CLASSES_IN_IR: true
// OMIT_REDUNDANT_MIRRORS: true

// MODULE: lib
// COMPANION_BLOCKS: true
// COMPANION_MODE: COMPANION_BLOCK
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
abstract class LibraryBindings {
  companion {
    @Provides
    @Named("value")
    fun value(): String = "static producer"

    @get:Provides
    val values: List<String>
      get() = listOf("static producer")
  }
}

@DependencyGraph(bindingContainers = [LibraryBindings::class])
interface LibraryGraph {
  @get:Named("value") val value: String
}

// MODULE: main(lib)
// COMPANION_BLOCKS: false
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Main.kt

@DependencyGraph(bindingContainers = [LibraryBindings::class])
interface AppGraph {
  val generic: GenericValue<String>
  val factory: AssistedValueFactory
  val injector: MembersInjector<GenericValue<String>>
}

fun box(): String {
  val graph = AppGraph()
  assertEquals("static producer", graph.generic.value)
  assertEquals(listOf("static producer"), graph.generic.values)
  graph.generic.values = emptyList()
  graph.injector.injectMembers(graph.generic)
  assertEquals(listOf("static producer"), graph.generic.values)
  val assisted = graph.factory.create(7)
  assertEquals(7, assisted.id)
  assertEquals("static producer", assisted.value)
  assertEquals("static producer", createGraph<LibraryGraph>().value)
  return "OK"
}
