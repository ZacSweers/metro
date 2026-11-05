// A Provider of an @Includes accessor should call the accessor on each get(), not when the
// extension builds the consumer.

class Service

interface Dependencies {
  val service: Service
}

class CountingDependencies : Dependencies {
  var reads = 0

  override val service: Service
    get() {
      reads++
      return Service()
    }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes dependencies: Dependencies): AppGraph
  }
}

abstract class ChildScope private constructor()

@Inject class Consumer(val service: Provider<Service>)

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val consumer: Consumer

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val dependencies = CountingDependencies()
  val consumer = createGraphFactory<AppGraph.Factory>().create(dependencies).create().consumer
  assertEquals(0, dependencies.reads)
  consumer.service()
  consumer.service()
  assertEquals(2, dependencies.reads)
  return "OK"
}
