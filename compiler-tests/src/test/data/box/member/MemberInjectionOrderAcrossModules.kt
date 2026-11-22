// MODULE: lib
class Target {
  val injected = mutableListOf<String>()

  @Inject fun start(@Named("start") value: String) {
    injected += "start=$value"
  }

  @Inject fun checkpoint() {
    injected += "checkpoint"
  }

  @Inject fun finish(@Named("finish") value: String) {
    injected += "finish=$value"
  }
}

// MODULE: main(lib)
@DependencyGraph
interface Graph {
  val injector: MembersInjector<Target>

  fun inject(target: Target)

  @Provides @Named("start") fun start(): String = "first"

  @Provides @Named("finish") fun finish(): String = "last"
}

fun box(): String {
  val graph = createGraph<Graph>()
  val expected = listOf("start=first", "checkpoint", "finish=last")
  assertEquals(expected, Target().also(graph.injector::injectMembers).injected.toList())
  assertEquals(expected, Target().also(graph::inject).injected.toList())
  return "OK"
}
