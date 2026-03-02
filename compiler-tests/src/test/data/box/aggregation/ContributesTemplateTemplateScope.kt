import kotlin.reflect.KClass

private var callCount = 0

@ContributesTemplate.Template
object ScopedTemplate {
  @Provides @SingleIn(ContributesTemplate.TemplateScope::class) @IntoSet fun <T : Any> contributeToSet(target: T): Any = target.also { callCount++ }
}

@ContributesTemplate(template = ScopedTemplate::class)
annotation class ScopedContainer(val scope: KClass<*>)

@ScopedContainer(AppScope::class)
@Inject
class SomeTarget

@DependencyGraph(AppScope::class)
interface AppGraph {
  val things: Set<Any>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  graph.things
  graph.things
  assertEquals(1, callCount)
  return "OK"
}
