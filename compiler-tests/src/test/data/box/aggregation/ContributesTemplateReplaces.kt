import kotlin.reflect.KClass

@ContributesTemplate.Template
object SetContainerTemplate {
  @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
}

@ContributesTemplate(template = SetContainerTemplate::class)
annotation class SetContainer(val scope: KClass<*>)

@SetContainer(AppScope::class)
@Inject
class OriginalTarget

// Replaces the generated binding container for OriginalTarget via @Origin
@ContributesTo(AppScope::class, replaces = [OriginalTarget::class])
@BindingContainer
object ReplacementBinding {
  @Provides @IntoSet fun contributeReplacement(): Any = "replaced"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val things: Set<Any>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(1, graph.things.size)
  assertEquals("replaced", graph.things.first())
  return "OK"
}
