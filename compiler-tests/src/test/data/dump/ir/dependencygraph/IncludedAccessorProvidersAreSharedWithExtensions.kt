// Child graphs read the parent's shared accessor provider instead of wrapping its getter in
// lambdas.

class Service

interface Deps {
  val service: Service
}

@Inject class Consumer(val service: Service)

@Inject class ProviderConsumer(val service: Provider<Service>)

@DependencyGraph
interface ParentGraph {
  val child: ChildGraph

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes deps: Deps): ParentGraph
  }
}

@GraphExtension
interface ChildGraph {
  val consumer: Consumer
  val providerConsumer: ProviderConsumer
  val service: Provider<Service>
}
