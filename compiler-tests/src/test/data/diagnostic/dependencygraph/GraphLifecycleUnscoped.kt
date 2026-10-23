// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY

@Inject class Service
class Input

@DependencyGraph interface AppGraph : CloseableGraph {
  val service: Service
  @OnGraphInit fun start(<!UNSCOPED_LIFECYCLE_PARAMETER!>service: Service<!>, later: () -> Service, input: Input) {}
  @Suppress("UNSCOPED_LIFECYCLE_PARAMETER")
  @OnGraphClose fun stop(service: Service) {}

  @DependencyGraph.Factory fun interface Factory {
    fun create(@Provides input: Input): AppGraph
  }
}
