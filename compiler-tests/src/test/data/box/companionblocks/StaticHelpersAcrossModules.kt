// MIN_COMPILER_VERSION: 2.5.0-Beta1
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt

@Inject
class GenericValue<T : CharSequence> private constructor(val value: T) {
  @Inject lateinit var values: List<T>
}

@AssistedInject
class AssistedValue<T : CharSequence> private constructor(@Assisted val id: Int, val value: T)

@AssistedFactory
interface AssistedValueFactory<T : CharSequence> {
  fun create(id: Int): AssistedValue<T>
}

class Message(val text: String)

@BindingContainer
abstract class LibraryBindings {
  companion {
    @Provides private fun message(value: String): Message = Message(value)
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPATIBILITY
// FILE: Main.kt

@DependencyGraph(bindingContainers = [LibraryBindings::class])
interface AppGraph {
  val generic: GenericValue<String>
  val factory: AssistedValueFactory<String>
  val membersInjector: MembersInjector<GenericValue<String>>
  val message: Message

  @Provides fun value(): String = "external"
  @Provides fun values(): List<String> = listOf("external")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("external", graph.generic.value)
  assertEquals("external", graph.message.text)
  assertEquals(listOf("external"), graph.generic.values)
  graph.membersInjector.injectMembers(graph.generic)
  assertEquals(listOf("external"), graph.generic.values)
  val assisted = graph.factory.create(7)
  assertEquals(7, assisted.id)
  assertEquals("external", assisted.value)
  return "OK"
}
