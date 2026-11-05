// GENERATE_CONTRIBUTION_PROVIDERS: true
// FIX_EMPTY_SMAP_RANGES
// https://github.com/ZacSweers/metro/pull/3046
// FIR contribution provider holders live in synthetic files without source lines.
// Factories that inline provider {} for default values must still emit nonempty SMAP ranges.
interface Service {
  fun describe(): String
}

interface Plugin {
  val name: String
}

class Client(val name: String)

@ContributesBinding(AppScope::class)
@Inject
class NullableDefaultService(private val client: Client? = null) : Service {
  override fun describe(): String = "client=" + (client?.name ?: "none")
}

@ContributesIntoSet(AppScope::class)
@Inject
class NonNullDefaultPlugin(private val client: Client = Client("default")) : Plugin {
  override val name: String
    get() = "plugin:" + client.name
}

class Outer {
  @ContributesIntoSet(AppScope::class)
  @Inject
  class NestedPlugin(private val label: String? = null) : Plugin {
    override val name: String
      get() = "nested:" + (label ?: "none")
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
  val plugins: Set<Plugin>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("client=none", graph.service.describe())
  assertEquals(setOf("plugin:default", "nested:none"), graph.plugins.map { it.name }.toSet())
  return "OK"
}
