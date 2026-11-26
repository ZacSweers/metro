// A child builds its own map from a scoped parent entry and its own entry. Both entries use an
// implicit class key. The parent entry's class comes from the parent's provider.

import kotlin.reflect.KClass

interface Base

@Inject class Impl : Base

@Inject class Other : Base

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  @SingleIn(AppScope::class)
  @Provides
  @IntoMap
  @ClassKey
  fun provideParentEntry(impl: Impl): Base = impl
}

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  @Provides @IntoMap @ClassKey fun provideChildEntry(other: Other): Base = other
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val map: Map<KClass<*>, Base>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<KClass<*>, Base>
  val childGraph: ChildGraph
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals(setOf<KClass<*>>(Impl::class), appGraph.map.keys)
  val parentEntry = appGraph.map.getValue(Impl::class)
  val childMap = appGraph.childGraph.map
  assertEquals(setOf<KClass<*>>(Impl::class, Other::class), childMap.keys)
  assertSame(parentEntry, childMap.getValue(Impl::class))
  assertTrue(childMap.getValue(Other::class) is Other)
  return "OK"
}
