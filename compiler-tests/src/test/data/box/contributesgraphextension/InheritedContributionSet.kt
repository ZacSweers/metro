abstract class LoggedInScope
interface ContributedInterface
class Impl1 : ContributedInterface
interface ConsumerInterface

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val consumer: ConsumerInterface

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@ContributesBinding(LoggedInScope::class)
class MultibindingConsumer @Inject constructor(val contributions: Set<ContributedInterface>) : ConsumerInterface

@ContributesTo(AppScope::class)
interface MultibindingsModule {

  @Provides
  @ElementsIntoSet
  fun provideImpl1(): Set<ContributedInterface> = setOf(Impl1())
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributions: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val child = graph.createLoggedInGraph()
  val consumer = assertIs<MultibindingConsumer>(child.consumer)
  assertEquals(Impl1::class, consumer.contributions.single()::class)
  assertEquals(Impl1::class, graph.contributions.single()::class)
  return "OK"
}
