import kotlin.reflect.KClass

@ContributesTemplate.Template
object AddToSetTemplate {
  @Provides @IntoSet fun <T : Any> intoSet(target: T): Any = target
}

@ContributesTemplate(template = AddToSetTemplate::class)
annotation class AddToSet(val scope: KClass<*>)

@AddToSet(AppScope::class)
object FeatureA

@AddToSet(AppScope::class)
object FeatureB

@DependencyGraph(AppScope::class)
interface AppGraph {
  val features: Set<Any>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf(FeatureA, FeatureB), graph.features)
  return "OK"
}
