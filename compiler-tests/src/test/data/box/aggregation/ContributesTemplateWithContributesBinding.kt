import kotlin.reflect.KClass

// MODULE: lib
@ContributesTemplate.Template
object ContributesMultibindingScopedTemplate {
  @Provides @IntoSet fun <T : Any> intoSet(target: T): Any = target
}

@ContributesTemplate(template = ContributesMultibindingScopedTemplate::class)
annotation class ContributesMultibindingScoped(val scope: KClass<*>)

// MODULE: main(lib)
interface ForegroundActivityProvider

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<ForegroundActivityProvider>())
@ContributesMultibindingScoped(AppScope::class)
@Inject
class ActivityListener : ForegroundActivityProvider

@DependencyGraph(AppScope::class)
interface AppGraph {
  val provider: ForegroundActivityProvider
  val allListeners: Set<Any>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val provider = graph.provider
  assertIs<ActivityListener>(provider)
  // Verify it's scoped (same instance)
  assertSame(provider, graph.provider)
  // Verify it's also contributed into set
  assertEquals(1, graph.allListeners.size)
  assertContains(graph.allListeners, provider)
  return "OK"
}
