// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: true

import java.lang.reflect.Modifier

@Inject
class InjectedValue private constructor(val value: String) {
  @Inject lateinit var values: List<String>
}

@Inject
class NoDependencyValue

@DependencyGraph
interface AppGraph {
  val value: InjectedValue
  val noDependency: NoDependencyValue

  @Provides fun value(): String = "bytecode"
  @Provides fun values(): List<String> = listOf("bytecode")
}

fun box(): String {
  assertEquals("bytecode", createGraph<AppGraph>().value.value)
  val factories = InjectedValue::class.java.declaredClasses
  val factory = factories.single { it.simpleName == "MetroFactory" }
  val injector = factories.single { it.simpleName == "MetroMembersInjector" }
  val noDependencyFactory = NoDependencyValue::class.java.declaredClasses.single {
    it.simpleName == "MetroFactory"
  }
  val factoryHelperNames = setOf("create", "newInstance")
  val expectedHelperNames =
    mapOf(
      factory to factoryHelperNames,
      injector to setOf("create", "injectValues"),
      noDependencyFactory to factoryHelperNames,
    )
  for ((helper, expectedNames) in expectedHelperNames) {
    assertFalse(helper.declaredClasses.any { it.simpleName == "Companion" })
    val helperMethods = helper.declaredMethods.filter { it.name in expectedNames }
    assertEquals(expectedNames, helperMethods.mapTo(mutableSetOf()) { it.name })
    assertTrue(helperMethods.all { Modifier.isStatic(it.modifiers) })
  }
  return "OK"
}
