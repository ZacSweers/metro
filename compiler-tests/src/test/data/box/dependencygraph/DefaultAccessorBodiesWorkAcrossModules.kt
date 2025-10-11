// https://github.com/ZacSweers/metro/issues/1154

// MODULE: lib
@SingleIn(Unit::class)
@GraphExtension(Unit::class)
interface UnitGraph {

  @GraphExtension.Factory
  interface Factory {
    fun create(
      @Provides name: String
    ): UnitGraph
  }

  @ContributesTo(AppScope::class)
  interface ParentBindings {
    fun factoryForUnitGraph(): Factory

    fun createUnitGraph(name: String): UnitGraph = factoryForUnitGraph().create(name)
  }
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertNotNull(appGraph.factoryForUnitGraph())
  assertNotNull(appGraph.createUnitGraph("units"))
  return "OK"
}