// ENABLE_SWITCHING_PROVIDERS: true

// Included accessors dispatch through the graph's SwitchingProvider instead of their own lambdas.

class Service

interface Deps {
  val service: Service
}

@Inject class Consumer(val service: Service)

@Inject class ProviderConsumer(val service: Provider<Service>)

@DependencyGraph
interface AppGraph {
  val consumer: Provider<Consumer>
  val providerConsumer: ProviderConsumer

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes deps: Deps): AppGraph
  }
}
