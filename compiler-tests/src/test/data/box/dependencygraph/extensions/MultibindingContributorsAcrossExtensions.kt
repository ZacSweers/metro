// https://github.com/ZacSweers/metro/issues/1598

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  private var stringFirstCount = 0
  private var intOneCount = 0

  @SingleIn(AppScope::class) @Provides fun provideSimpleString(): String = "simple"

  @SingleIn(AppScope::class)
  @Provides
  @IntoMap
  @StringKey("key1")
  fun provideFirst(): String {
    stringFirstCount++
    check(stringFirstCount == 1)
    return "first"
  }

  @Provides @IntoMap @StringKey("key2") fun provideSecond(): String = "second"

  @SingleIn(AppScope::class) @Provides @IntoSet fun provide1(): Int {
    intOneCount++
    check(intOneCount == 1)
    return 1
  }

  @Provides @IntoSet fun provide2(): Int = 2
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val simpleStringFromAppScope: String
  val mapFromAppScope: Map<String, String>
  val setFromAppScope: Set<Int>

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val simpleString: String
  val map: Map<String, String>
  val set: Set<Int>
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals("simple", appGraph.simpleString)
  assertEquals(mapOf("key1" to "first", "key2" to "second"), appGraph.map)
  assertEquals(setOf(2, 1), appGraph.set)

  val childGraph = appGraph.createChildGraph()
  assertEquals("simple", childGraph.simpleStringFromAppScope)
  assertEquals(mapOf("key1" to "first", "key2" to "second"), childGraph.mapFromAppScope)
  assertEquals(setOf(2, 1), childGraph.setFromAppScope)
  return "OK"
}
