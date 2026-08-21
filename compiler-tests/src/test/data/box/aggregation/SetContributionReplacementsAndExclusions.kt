interface OtherScope
interface Plugin {
  val value: String
}
interface RetainedService

@Inject
@ContributesBinding(AppScope::class, binding = binding<RetainedService>())
@ContributesIntoSet(AppScope::class, binding = binding<Plugin>())
@ContributesIntoMap(
  AppScope::class,
  binding = binding<@StringKey("retained") Plugin>(),
)
class FirstPlugin : Plugin, RetainedService {
  override val value = "first"
}

@Inject
@ContributesIntoSet(AppScope::class)
class SecondPlugin : Plugin {
  override val value = "second"
}

@Inject
@ContributesIntoSet(AppScope::class)
class ReplacedPlugin : Plugin {
  override val value = "replaced"
}

@Inject
@ContributesIntoSet(AppScope::class, replaces = [ReplacedPlugin::class])
class ReplacementPlugin : Plugin {
  override val value = "replacement"
}

@Inject
@ContributesIntoSet(AppScope::class)
class ExcludedPlugin : Plugin {
  override val value = "excluded"
}

@Inject
@Named("qualified")
@ContributesIntoSet(AppScope::class)
class QualifiedPlugin : Plugin {
  override val value = "qualified"
}

@Inject
@ContributesIntoSet(OtherScope::class)
class OtherScopePlugin : Plugin {
  override val value = "other scope"
}

class AuthoredPlugin : Plugin {
  override val value = "authored"
}

@ContributesTo(AppScope::class)
@BindingContainer
object AuthoredBindings {
  @Provides @IntoSet fun authoredPlugin(): Plugin = AuthoredPlugin()
}

@DependencyGraph(AppScope::class, excludes = [ExcludedPlugin::class])
interface AppGraph {
  val plugins: Set<Plugin>
  @Named("qualified") val qualifiedPlugins: Set<Plugin>
  val retainedService: RetainedService
  val pluginMap: Map<String, Plugin>
}

@DependencyGraph(OtherScope::class)
interface OtherGraph {
  val plugins: Set<Plugin>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(
    setOf("first", "second", "replacement", "authored"),
    graph.plugins.map { it.value }.toSet(),
  )
  assertEquals("qualified", graph.qualifiedPlugins.single().value)
  assertIs<FirstPlugin>(graph.retainedService)
  assertIs<FirstPlugin>(graph.pluginMap.getValue("retained"))
  assertEquals("other scope", createGraph<OtherGraph>().plugins.single().value)
  return "OK"
}
