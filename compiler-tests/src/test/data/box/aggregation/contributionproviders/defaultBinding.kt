// MODULE: lib
interface NavKey

@DefaultBinding<NavEntry<*>> interface NavEntry<T : NavKey>

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Qualified

interface Service

@DefaultBinding<@Qualified Service> interface QualifiedService : Service

// MODULE: main(lib)
object HomeKey : NavKey

@ContributesIntoSet(AppScope::class) object HomeEntry : NavEntry<HomeKey>

object ProfileKey : NavKey

@ContributesIntoSet(AppScope::class) object ProfileEntry : NavEntry<ProfileKey>

@Inject class NavEntryRegistry(val entries: Set<NavEntry<*>>)

@ContributesBinding(AppScope::class) @Inject class QualifiedServiceImpl : QualifiedService

@DependencyGraph(AppScope::class)
interface AppGraph {
  val navEntryRegistry: NavEntryRegistry
  @Qualified val qualifiedService: Service
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val navEntries = graph.navEntryRegistry.entries
  assertEquals(2, navEntries.size)
  assertEquals(setOf(HomeEntry, ProfileEntry), navEntries)
  assertTrue(graph.qualifiedService is QualifiedServiceImpl)
  return "OK"
}
