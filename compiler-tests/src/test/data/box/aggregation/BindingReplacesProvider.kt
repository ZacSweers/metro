interface Base {
  val value: String
}

@ContributesTo(AppScope::class)
interface RealProvider {
  @Provides
  fun provideReal(): Base = object : Base {
    override val value: String = "Real"
  }
}

@Inject
@ContributesBinding(AppScope::class, replaces = [RealProvider::class])
class Fake: Base {
  override val value: String = "Fake"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val base: Base
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Fake", graph.base.value)
  return "OK"
}
