import kotlin.reflect.KClass

sealed interface FeatureScope

@ContributesTemplate
annotation class FeatureA(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
  }
}

@ContributesTemplate
annotation class FeatureB(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
  }
}

@FeatureA(AppScope::class)
@FeatureB(FeatureScope::class)
@Inject
class MyTarget

@DependencyGraph(AppScope::class)
interface AppGraph {
  val features: Set<Any>
}

@DependencyGraph(FeatureScope::class)
interface FeatureGraph {
  val features: Set<Any>
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals(1, appGraph.features.size)
  val featureGraph = createGraph<FeatureGraph>()
  assertEquals(1, featureGraph.features.size)
  return "OK"
}
