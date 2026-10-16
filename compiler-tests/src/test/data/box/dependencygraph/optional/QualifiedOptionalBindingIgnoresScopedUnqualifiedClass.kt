@Qualifier
annotation class Marker

sealed interface OtherScope

@SingleIn(AppScope::class)
@Inject
class Tracker

@DependencyGraph(OtherScope::class)
interface ExampleGraph {
  val description: String

  @Provides
  fun provideDescription(@OptionalBinding @Marker tracker: Tracker = Tracker()): String {
    return "tracker=" + tracker::class.simpleName
  }
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("tracker=Tracker", graph.description)
  return "OK"
}
