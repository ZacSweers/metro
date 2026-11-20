abstract class LoggedInScope
interface ContributedInterface
class Impl1 : ContributedInterface

@ContributesTo(AppScope::class)
interface MultibindingsModule {

  @Provides
  @ElementsIntoSet
  fun provideImpl1(): Set<ContributedInterface> = setOf(Impl1())
}

class MultibindingConsumer @Inject constructor(val contributions: Set<ContributedInterface>)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val multibindingConsumer: MultibindingConsumer
}

fun box(): String {
  val contributions = createGraph<ExampleGraph>().multibindingConsumer.contributions
  assertEquals(Impl1::class, contributions.single()::class)
  return "OK"
}
