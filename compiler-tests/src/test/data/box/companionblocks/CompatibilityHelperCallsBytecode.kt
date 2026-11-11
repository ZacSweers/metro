// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: true
// CHECK_BYTECODE_TEXT
// MODULE: lib
// COMPANION_MODE: COMPATIBILITY
// FILE: Library.kt

@Inject
class LibValue private constructor(val text: String) {
  @Inject lateinit var values: List<String>
}

class LibMessage(val text: String)

@BindingContainer
abstract class LibBindings {
  companion {
    @Provides private fun libMessage(text: String): LibMessage = LibMessage(text)
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPATIBILITY
// FILE: Main.kt

@Inject
class LocalValue private constructor(val text: String) {
  @Inject lateinit var values: List<String>
}

class LocalMessage(val text: String)

@BindingContainer
abstract class LocalBindings {
  companion {
    @Provides private fun localMessage(text: String): LocalMessage = LocalMessage(text)
  }
}

@DependencyGraph(bindingContainers = [LibBindings::class, LocalBindings::class])
interface AppGraph {
  val lib: LibValue
  val local: LocalValue
  val libMessage: LibMessage
  val localMessage: LocalMessage

  @Provides fun text(): String = "OK"

  @Provides fun values(): List<String> = listOf("values")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("OK", graph.lib.text)
  assertEquals(listOf("values"), graph.lib.values)
  assertEquals("OK", graph.local.text)
  assertEquals(listOf("values"), graph.local.values)
  assertEquals("OK", graph.libMessage.text)
  assertEquals("OK", graph.localMessage.text)
  return "OK"
}

// Graph code calls the static class-level helpers and never loads a helper companion.
// @AppGraph$Impl.class:
// 0 GETSTATIC .*\.Companion
// 0 INVOKEVIRTUAL .*\$Companion\.
// 1 INVOKESTATIC LibValue\$MetroFactory\.create
// 1 INVOKESTATIC LocalValue\$MetroFactory\.create
// 1 INVOKESTATIC LibBindings\$LibMessageMetroFactory\.libMessage
// 1 INVOKESTATIC LocalBindings\$LocalMessageMetroFactory\.localMessage
// 2 INVOKESTATIC AppGraph\$TextMetroFactory\.create
