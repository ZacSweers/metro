// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
// Regression test for https://github.com/ZacSweers/metro/issues/2149

import com.squareup.anvil.annotations.ContributesSubcomponent

@DependencyGraph(AppScope::class)
@SingleIn(AppScope::class)
interface AppGraph

abstract class MidScope private constructor()

@GraphExtension(scope = MidScope::class)
@SingleIn(MidScope::class)
interface MidGraph {
  @ContributesTo(AppScope::class)
  interface Parent {
    fun midGraph(): MidGraph
  }
}

abstract class LeafScope private constructor()

class LeafValue(val id: Long)

@ContributesSubcomponent(scope = LeafScope::class, parentScope = MidScope::class)
@SingleIn(LeafScope::class)
interface LeafGraph {
  val value: LeafValue

  @ContributesTo(MidScope::class)
  @ContributesSubcomponent.Factory
  interface Factory {
    fun create(@Provides value: LeafValue): LeafGraph
  }

  @ContributesTo(MidScope::class)
  interface Parent2 {
    fun leafFactory(): Factory
  }
}

fun box(): String {
  val app = createGraph<AppGraph>()
  val mid = app.midGraph()
  val leaf = (mid as LeafGraph.Parent2).leafFactory().create(LeafValue(1))
  assertEquals(1L, leaf.value.id)
  return "OK"
}