// A child reads a map that holds a scoped parent entry with an implicit class key.

import kotlin.reflect.KClass

interface Base

@Inject class Impl : Base

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
  val parentEntry = appGraph.map.getValue(Impl::class)
  val childMap = appGraph.childGraph.map
  assertEquals(setOf<KClass<*>>(Impl::class), childMap.keys)
  assertSame(parentEntry, childMap.getValue(Impl::class))
  return "OK"
}
