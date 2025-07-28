// MODULE: lib
@SingleIn(Unit::class)
@ContributesGraphExtension(Unit::class)
interface UnitGraph {
  fun inject(target: StringHolder)

  @SingleIn(Unit::class)
  @Provides
  fun provideString(): String {
    return "hello"
  }

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createUnitGraph(): UnitGraph
  }
}

class StringHolder {
  @Inject lateinit var string: String
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class, isExtendable = true)
interface AppGraph

fun box(): String {
  val holder = StringHolder()
  val graph = createGraph<AppGraph>().createUnitGraph()
  graph.inject(holder)
  assertEquals("hello", holder.string)
  return "OK"
}