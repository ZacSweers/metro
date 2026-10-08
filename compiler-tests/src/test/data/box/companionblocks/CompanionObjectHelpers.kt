// MIN_COMPILER_VERSION: 2.5.0-Beta1
// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: false
// MODULE: lib
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Library.kt

@Inject
class GenericValue<T : CharSequence> private constructor(val value: T) {
  @Inject lateinit var values: List<T>
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Main.kt

import java.lang.reflect.Modifier

@DependencyGraph
interface AppGraph {
  val value: GenericValue<String>

  @Provides fun value(): String = "graph"
  @Provides fun values(): List<String> = listOf("graph-members")
}

@Suppress("DEPRECATION_ERROR")
fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("graph", graph.value.value)
  assertEquals(listOf("graph-members"), graph.value.values)

  val value = GenericValue.MetroFactory.Companion.newInstance("companion")
  GenericValue.MetroMembersInjector.Companion.injectValues(value, listOf("members"))
  assertEquals("companion", value.value)
  assertEquals(listOf("members"), value.values)

  val helpers = GenericValue::class.java.declaredClasses
  val expectedNames =
    mapOf("MetroFactory" to setOf("create", "newInstance"),
      "MetroMembersInjector" to setOf("create", "injectValues"))
  for ((name, names) in expectedNames) {
    val owner = helpers.single { it.simpleName == name }
    val companion = owner.declaredClasses.single { it.simpleName == "Companion" }
    val methods = companion.declaredMethods.filter { it.name in names }
    assertEquals(names, methods.mapTo(mutableSetOf()) { it.name })
    assertTrue(methods.all { !Modifier.isStatic(it.modifiers) })
    assertTrue(methods.all { it.typeParameters.single().name == "T" })
  }
  return "OK"
}
