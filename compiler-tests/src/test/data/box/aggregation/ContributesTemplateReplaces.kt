import kotlin.reflect.KClass

@ContributesTemplate
annotation class SetContainer(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> contributeToSet(target: T): Any = target
  }
}

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
