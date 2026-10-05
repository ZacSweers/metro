// MIN_COMPILER_VERSION: 2.5.0-Beta1
// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: false
// MODULE: lib
// COMPANION_MODE: COMPATIBILITY
// FILE: Library.kt

@Inject
class GenericValue<T : CharSequence> private constructor(val value: T, val tag: String = "default") {
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
// COMPANION_MODE: COMPANION_BLOCK
// FILE: JavaHelpers.java

public final class JavaHelpers {
  public static GenericValue<String> create() {
    GenericValue<String> value = GenericValue.MetroFactory.newInstance("java", "java-tag");
    GenericValue.MetroMembersInjector.injectValues(
        value, java.util.Collections.singletonList("java-members"));
    return value;
  }
}

// FILE: Main.kt

import java.lang.reflect.Modifier

@DependencyGraph(bindingContainers = [LibraryBindings::class])
interface AppGraph {
  val value: GenericValue<String>
  val assistedFactory: AssistedValueFactory<String>
  val message: Message

  @Provides fun value(): String = "graph"
  @Provides fun values(): List<String> = listOf("graph-members")
}

@Suppress("DEPRECATION_ERROR")
fun box(): String {
  val graph = AppGraph()
  assertEquals("graph", graph.value.value)
  assertEquals("graph", graph.message.text)
  assertEquals(listOf("graph-members"), graph.value.values)
  assertEquals("graph", graph.assistedFactory.create(7).value)

  val canonical = GenericValue.MetroFactory.Companion.newInstance("companion")
  GenericValue.MetroMembersInjector.Companion.injectValues(canonical, listOf("companion-members"))
  assertEquals("companion", canonical.value)
  assertEquals("default", canonical.tag)
  assertEquals(listOf("companion-members"), canonical.values)

  val direct = GenericValue.MetroFactory.newInstance("static")
  GenericValue.MetroMembersInjector.injectValues(direct, listOf("static-members"))
  assertEquals("static", direct.value)
  assertEquals("default", direct.tag)
  assertEquals(listOf("static-members"), direct.values)
  val javaValue = JavaHelpers.create()
  assertEquals("java", javaValue.value)
  assertEquals("java-tag", javaValue.tag)
  assertEquals(listOf("java-members"), javaValue.values)

  val canonicalFactory = GenericValue.MetroFactory.Companion.create(
    Provider { "factory-companion" }, Provider { "factory-tag" },
    Provider { listOf("factory-members") },
  )
  val staticFactory = GenericValue.MetroFactory.create(
    Provider { "factory-static" }, Provider { "factory-tag" },
    Provider { listOf("factory-members") },
  )
  assertEquals("factory-companion", canonicalFactory.invoke().value)
  assertEquals("factory-static", staticFactory.invoke().value)
  val canonicalInjector = GenericValue.MetroMembersInjector.Companion.create(
    Provider { listOf("injector-companion") }
  )
  val staticInjector = GenericValue.MetroMembersInjector.create(
    Provider { listOf("injector-static") }
  )
  canonicalInjector.injectMembers(canonical)
  staticInjector.injectMembers(direct)
  assertEquals(listOf("injector-companion"), canonical.values)
  assertEquals(listOf("injector-static"), direct.values)

  val helpers = GenericValue::class.java.declaredClasses
  val expectedNames =
    mapOf("MetroFactory" to setOf("create", "newInstance"),
      "MetroMembersInjector" to setOf("create", "injectValues"))
  for ((name, names) in expectedNames) {
    val owner = helpers.single { it.simpleName == name }
    val companion = owner.declaredClasses.single { it.simpleName == "Companion" }
    val statics = owner.declaredMethods.filter { it.name in names }
    val canonicalMethods = companion.declaredMethods.filter { it.name in names }
    assertEquals(names.size, statics.size)
    assertEquals(names, statics.mapTo(mutableSetOf()) { it.name })
    assertEquals(names, canonicalMethods.mapTo(mutableSetOf()) { it.name })
    assertTrue(statics.all { Modifier.isStatic(it.modifiers) })
    assertTrue(canonicalMethods.all { !Modifier.isStatic(it.modifiers) })
    assertTrue(statics.all { it.typeParameters.single().name == "T" })
  }
  return "OK"
}
