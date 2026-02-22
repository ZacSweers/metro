import kotlin.reflect.KClass

@ContributesTemplate
annotation class AddToSet(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> intoSet(target: T): Any = target
  }
}

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
