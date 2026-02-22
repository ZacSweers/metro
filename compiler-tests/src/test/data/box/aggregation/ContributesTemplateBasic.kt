import kotlin.reflect.KClass

@ContributesTemplate
annotation class MyContainer(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> contributeToAnySet(target: T): Any = target
    @Provides @IntoSet fun <T : Any> contributeNameToSet(target: T): String = target::class.simpleName!!
  }
}

@MyContainer(AppScope::class)
@Inject
class SomeTarget

@DependencyGraph(AppScope::class)
interface AppGraph {
  val things: Set<Any>
  val names: Set<String>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(1, graph.things.size)
  assertEquals(setOf("SomeTarget"), graph.names)
  return "OK"
}
