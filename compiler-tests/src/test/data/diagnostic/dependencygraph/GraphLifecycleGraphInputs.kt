// RUN_PIPELINE_TILL: BACKEND
// METRO_JVM_ONLY

class Input
class ChildInput
interface Service
class ServiceImpl : Service

// @Provides graph inputs are single instances, so callbacks can request them directly. This covers
// an extension's own input, its parent's input, and an input reached through @Binds.
@GraphExtension
interface Child : CloseableGraph {
  @OnGraphInit fun start(childInput: ChildInput, input: Input, service: Service) {}
  @OnGraphClose fun stop(childInput: ChildInput, input: Input) {}

  @GraphExtension.Factory fun interface Factory { fun create(@Provides childInput: ChildInput): Child }
}

@DependencyGraph
interface AppGraph : CloseableGraph {
  val childFactory: Child.Factory

  @Binds val ServiceImpl.bind: Service

  @OnGraphInit fun start(input: Input, service: Service) {}
  @OnGraphClose fun stop(input: Input, impl: ServiceImpl) {}

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides input: Input, @Provides impl: ServiceImpl): AppGraph
  }
}
