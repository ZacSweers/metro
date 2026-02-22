import kotlin.reflect.KClass

// MODULE: lib
@ContributesTemplate
annotation class MyContainer(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
  }
}

// MODULE: main(lib)
@MyContainer(AppScope::class)
@Inject
class SomeTarget

@DependencyGraph(AppScope::class)
interface AppGraph {
  val things: Set<Any>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(1, graph.things.size)
  return "OK"
}
