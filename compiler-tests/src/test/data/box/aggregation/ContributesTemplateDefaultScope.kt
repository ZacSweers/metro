import kotlin.reflect.KClass

sealed interface OtherScope

// No scope parameter — uses fixed scope from @ContributesTemplate
@ContributesTemplate(scope = AppScope::class)
annotation class FixedScopeContainer {
  companion object {
    @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
  }
}

// Has scope parameter on the custom annotation — per-usage scope
@ContributesTemplate
annotation class PerUsageScopeContainer(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
  }
}

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
