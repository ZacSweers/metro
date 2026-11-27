// A child map entry with a different key merges with a scoped parent entry.

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  @SingleIn(AppScope::class)
  @Provides
  @IntoMap
  @StringKey("parent")
  fun provideParentEntry(): String = "parent"
}

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  @Provides @IntoMap @StringKey("child") fun provideChildEntry(): String = "child"
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val map: Map<String, String>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<String, String>
  val childGraph: ChildGraph
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals(mapOf("parent" to "parent"), appGraph.map)
  assertEquals(mapOf("parent" to "parent", "child" to "child"), appGraph.childGraph.map)
  return "OK"
}
