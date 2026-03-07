@DependencyGraph
interface AppGraph {

  // Provider<Set<...>
  //   .. depends on ElementsIntoSet
  //   .. depends on another ElementsIntoSet
  //   .. depends on thing

  @Provides
  fun provideInt(): Int = 3

  @Named("first")
  @Binds
  @IntoSet
  fun bindIntAsFirstSet(int: Int): Int


  @Named("second")
  @ElementsIntoSet
  @Binds
  fun bindFirstAsSecond(@Named("first") second: Set<Int>): Set<Int>


  @Named("final")
  @ElementsIntoSet
  @Binds
  fun bindSecondAsFinal(@Named("second") second: Set<Int>): Set<Int>

  val int: Int
  val finalThing: FinalThing
}

@Inject
class FinalThing(@Named("final") val ints: Provider<Set<Int>>)

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.int)
  assertEquals(setOf(3), graph.finalThing.ints())
  return "OK"
}