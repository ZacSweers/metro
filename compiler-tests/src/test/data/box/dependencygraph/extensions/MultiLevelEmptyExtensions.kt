@GraphExtension
interface LandingGraph : AccountGraph.Factory {
  @GraphExtension.Factory
  fun interface Factory {
    fun create(): LandingGraph
  }
}

@GraphExtension
interface AccountGraph {
  @GraphExtension.Factory
  fun interface Factory {
    fun create(): AppGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph : LandingGraph.Factory {
  val test: Test
}

interface Test {
  val message: String
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class TestImpl : Test {
  private var count = 0
  override val message: String = count++.toString()
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  return "OK"
}