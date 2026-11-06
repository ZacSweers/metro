// A Provider of an @Includes accessor should call the accessor on each invocation.
// Building the extension or its consumer must leave the accessor unread.

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
  val sibling: SiblingGraph
  val siblingProvider: Provider<SiblingGraph>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes dependencies: Dependencies): AppGraph
  }
}

abstract class ChildScope private constructor()

@GraphExtension
interface SiblingGraph

@Inject class Consumer(val service: Provider<Service>)

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val consumer: Consumer
  val grandchild: GrandchildGraph
  val siblingProvider: Provider<SiblingGraph>

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph {
  val consumer: Consumer
  val service: Provider<Service>
  val lazyService: Lazy<Service>
  val serviceFunction: () -> Service
  val siblingProvider: Provider<SiblingGraph>
}

fun box(): String {
  val dependencies = CountingDependencies()
  val graph = createGraphFactory<AppGraph.Factory>().create(dependencies)
  val child = graph.create()
  assertEquals(0, dependencies.reads)
  val consumer = child.consumer
  assertEquals(0, dependencies.reads)
  assertNotSame(consumer.service(), consumer.service())
  assertEquals(2, dependencies.reads)

  val grandchild = child.grandchild
  val nestedConsumer = grandchild.consumer
  val provider = grandchild.service
  val lazyService = grandchild.lazyService
  val serviceFunction = grandchild.serviceFunction
  assertEquals(2, dependencies.reads)
  for (service in listOf(nestedConsumer.service, provider)) {
    val before = dependencies.reads
    assertNotSame(service(), service())
    assertEquals(before + 2, dependencies.reads)
  }
  assertNotSame(serviceFunction(), serviceFunction())
  assertEquals(8, dependencies.reads)
  assertSame(lazyService.value, lazyService.value)
  assertEquals(9, dependencies.reads)

  for (provider in listOf(graph.siblingProvider, child.siblingProvider, grandchild.siblingProvider)) {
    assertSame(provider(), provider())
  }
  return "OK"
}
