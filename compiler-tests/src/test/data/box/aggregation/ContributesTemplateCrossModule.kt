import kotlin.reflect.KClass

// MODULE: lib
@ContributesTemplate.Template
object MyContainerTemplate {
  @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
}

@ContributesTemplate(template = MyContainerTemplate::class)
annotation class MyContainer(val scope: KClass<*>)

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
