// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK
// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: false

import java.lang.reflect.Modifier

@Inject
class InjectedValue private constructor(val value: String) {
  @Inject lateinit var values: List<String>
}

@Inject
class SingletonValue

@DependencyGraph
interface AppGraph {
  val value: InjectedValue
  val singleton: SingletonValue

  @Provides fun value(): String = "bytecode"
  @Provides fun values(): List<String> = listOf("bytecode")
}

fun box(): String {
  assertEquals("bytecode", createGraph<AppGraph>().value.value)
  val factories = InjectedValue::class.java.declaredClasses
  val factory = factories.single { it.simpleName == "MetroFactory" }
  val injector = factories.single { it.simpleName == "MetroMembersInjector" }
  val expectedHelperNames =
    mapOf(factory to setOf("create", "newInstance"), injector to setOf("create", "injectValues"))
  for ((helper, expectedNames) in expectedHelperNames) {
    assertFalse(helper.declaredClasses.any { it.simpleName == "Companion" })
    val helperMethods = helper.declaredMethods.filter { it.name in expectedNames }
    assertEquals(expectedNames, helperMethods.mapTo(mutableSetOf()) { it.name })
    assertTrue(helperMethods.all { Modifier.isStatic(it.modifiers) })
  }
  val singletonFactory = SingletonValue::class.java.declaredClasses.single {
    it.simpleName == "MetroFactory"
  }
  assertNotNull(singletonFactory.getField("INSTANCE").get(null))
  return "OK"
}
