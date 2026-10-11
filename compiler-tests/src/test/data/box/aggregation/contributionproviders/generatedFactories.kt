// IGNORE_BACKEND: JS_IR
// MIN_COMPILER_VERSION: 2.3.20
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

/*
 * IR class generation used to add class factories and companions for implementations whose
 * contribution providers already handled construction. Those extra classes increased generated
 * code size. This fixture runs in both generation modes to check factory output and cross-module
 * construction, including the factories required by exposed implementations and assisted bindings.
 */

// MODULE: lib

// The consumer resolves this internal implementation through its contribution provider.
interface Service {
  val value: String
}

@ContributesBinding(AppScope::class)
@Inject
internal class ServiceImpl(override val value: String) : Service

// Both bindings share the instance created by the synthetic scoped provider.
interface FirstService {
  val value: String
}

interface SecondService

@ContributesBinding(AppScope::class, binding = binding<FirstService>())
@ContributesBinding(AppScope::class, binding = binding<SecondService>())
@SingleIn(AppScope::class)
@Inject
internal class SharedServiceImpl(override val value: String) : FirstService, SecondService

// Exposing the implementation keeps its class factory available to consumers.
interface ExposedService {
  val value: String
}

@ExposeImplBinding
@ContributesBinding(AppScope::class)
@Inject
class ExposedServiceImpl(override val value: String) : ExposedService

// The contributed assisted factory still needs the implementation's class factory.
interface AssistedService {
  val value: String

  interface Factory {
    fun create(suffix: String): AssistedService
  }
}

@AssistedInject
class AssistedServiceImpl(value: String, @Assisted suffix: String) : AssistedService {
  override val value: String = value + suffix

  @ContributesBinding(AppScope::class)
  @AssistedFactory
  interface Factory : AssistedService.Factory {
    override fun create(suffix: String): AssistedServiceImpl
  }
}

// MODULE: main(lib)

// Graph construction crosses the module boundary for each kind of contributed binding.
@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
  val firstService: FirstService
  val secondService: SecondService
  val exposedService: ExposedServiceImpl
  val assistedFactory: AssistedService.Factory

  @Provides
  fun provideValue(): String = "value"
}

// Check both class files because the factory companion contributes to emitted class count.
private fun assertNoClassFactory(instance: Any) {
  val implementationClass = instance.javaClass
  assertFalse(implementationClass.declaredClasses.any { it.simpleName == "MetroFactory" })
  for (suffix in listOf("\$MetroFactory", "\$MetroFactory\$Companion")) {
    assertFailsWith<ClassNotFoundException> {
      Class.forName(implementationClass.name + suffix, false, implementationClass.classLoader)
    }
  }
}

// Factories needed for direct or assisted construction retain their companions.
private fun assertClassFactoryRetained(instance: Any) {
  val factory = instance.javaClass.declaredClasses.single { it.simpleName == "MetroFactory" }
  assertTrue(factory.declaredClasses.any { it.simpleName == "Companion" })
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val service = graph.service
  assertEquals("value", service.value)
  assertNoClassFactory(service)

  val sharedService = graph.firstService
  assertEquals("value", sharedService.value)
  assertSame(sharedService, graph.firstService)
  assertSame<Any>(sharedService, graph.secondService)
  assertNoClassFactory(sharedService)

  val exposedService = graph.exposedService
  assertEquals("value", exposedService.value)
  assertClassFactoryRetained(exposedService)

  val assistedService = graph.assistedFactory.create("-assisted")
  assertEquals("value-assisted", assistedService.value)
  assertClassFactoryRetained(assistedService)
  return "OK"
}
