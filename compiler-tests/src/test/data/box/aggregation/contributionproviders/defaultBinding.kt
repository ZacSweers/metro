// MODULE: lib
interface NavKey

@DefaultBinding<NavEntry<*>>
interface NavEntry<T : NavKey>

// MODULE: main(lib)
object HomeKey : NavKey

@ContributesIntoSet(AppScope::class)
object HomeEntry : NavEntry<HomeKey>

object ProfileKey : NavKey

@ContributesIntoSet(AppScope::class)
object ProfileEntry : NavEntry<ProfileKey>

@Inject
class NavEntryRegistry(
  val entries: Set<NavEntry<*>>,
)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val navEntryRegistry: NavEntryRegistry
}

fun box(): String {
  val navEntries = createGraph<AppGraph>().navEntryRegistry.entries
  assertEquals(2, navEntries.size)
  assertEquals(setOf(HomeEntry, ProfileEntry), navEntries)
  return "OK"
}
