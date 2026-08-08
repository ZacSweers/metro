import kotlin.reflect.KClass

// MODULE: api
interface RouteKey

@MapKey(implicitClassKey = true)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.TYPE,
  AnnotationTarget.TYPE_PARAMETER,
)
annotation class RouteMapKey(val value: KClass<out RouteKey> = Nothing::class)

@DefaultBinding<RouteScreen<*>>
interface RouteScreen<@RouteMapKey T : RouteKey>

// MODULE: impl(api)
class HomeKey : RouteKey

@ContributesIntoMap(AppScope::class)
@Inject
class HomeScreen : RouteScreen<HomeKey>

class OverrideHomeKey : RouteKey

class AlternateHomeKey : RouteKey

@ContributesIntoMap(AppScope::class)
@Inject
class OverrideHomeScreen : RouteScreen<@RouteMapKey(AlternateHomeKey::class) OverrideHomeKey>

class ClassOverrideHomeKey : RouteKey

class ClassAlternateHomeKey : RouteKey

@RouteMapKey(ClassAlternateHomeKey::class)
@ContributesIntoMap(AppScope::class)
@Inject
class ClassOverrideHomeScreen : RouteScreen<ClassOverrideHomeKey>

// MODULE: main(api, impl)
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface RouteGraph {
  val screens: Map<KClass<out RouteKey>, RouteScreen<*>>
}

fun box(): String {
  val graph = createGraph<RouteGraph>()
  assertEquals(3, graph.screens.size)
  assertIs<HomeScreen>(graph.screens.getValue(HomeKey::class))
  assertIs<OverrideHomeScreen>(graph.screens.getValue(AlternateHomeKey::class))
  assertIs<ClassOverrideHomeScreen>(graph.screens.getValue(ClassAlternateHomeKey::class))
  assertFalse(graph.screens.containsKey(OverrideHomeKey::class))
  assertFalse(graph.screens.containsKey(ClassOverrideHomeKey::class))
  return "OK"
}
