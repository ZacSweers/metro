import kotlin.reflect.KClass

sealed interface FeatureScope

@ContributesTemplate.Template
object FeatureATemplate {
  @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
}

@ContributesTemplate.Template
object FeatureBTemplate {
  @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
}

@ContributesTemplate(template = FeatureATemplate::class)
annotation class FeatureA(val scope: KClass<*>)

@ContributesTemplate(template = FeatureBTemplate::class)
annotation class FeatureB(val scope: KClass<*>)

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
