import kotlin.reflect.KClass

sealed interface OtherScope

@ContributesTemplate.Template
object FixedScopeTemplate {
  @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
}

@ContributesTemplate.Template
object PerUsageScopeTemplate {
  @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
}

// No scope parameter — uses fixed scope from @ContributesTemplate
@ContributesTemplate(template = FixedScopeTemplate::class, scope = AppScope::class)
annotation class FixedScopeContainer

// Has scope parameter on the custom annotation — per-usage scope
@ContributesTemplate(template = PerUsageScopeTemplate::class)
annotation class PerUsageScopeContainer(val scope: KClass<*>)

@FixedScopeContainer
@Inject
class FixedTarget

@PerUsageScopeContainer(AppScope::class)
@Inject
class PerUsageAppTarget

@PerUsageScopeContainer(OtherScope::class)
@Inject
class PerUsageOtherTarget

@DependencyGraph(AppScope::class)
interface AppGraph {
  val things: Set<Any>
}

@DependencyGraph(OtherScope::class)
interface OtherGraph {
  val things: Set<Any>
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals(2, appGraph.things.size)
  assertTrue(appGraph.things.any { it is FixedTarget })
  assertTrue(appGraph.things.any { it is PerUsageAppTarget })

  val otherGraph = createGraph<OtherGraph>()
  assertEquals(1, otherGraph.things.size)
  assertTrue(otherGraph.things.any { it is PerUsageOtherTarget })
  return "OK"
}
