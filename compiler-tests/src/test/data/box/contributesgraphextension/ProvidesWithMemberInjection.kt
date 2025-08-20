// MODULE: lib
@SingleIn(Unit::class)
@GraphExtension(Unit::class)
interface UnitGraph {
  fun inject(target: StringHolder)

  @SingleIn(Unit::class)
  @Provides
  fun provideString(): String {
    return "hello"
  }

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createUnitGraph(): UnitGraph
  }
}

class StringHolder {
  @Inject lateinit var string: String
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val holder = StringHolder()
  val graph = createGraph<AppGraph>().createUnitGraph()
  graph.inject(holder)
  assertEquals("hello", holder.string)
  return "OK"
}