@DependencyGraph
interface BindingGraph {
  @Binds val Int.unusedBinding1: Number
  @Binds val Int.unusedBinding2: Number
}

@DependencyGraph
interface ProvidesGraph {
  @Provides fun provideUnusedBinding1(): Number = 1
  @Provides fun provideUnusedBinding2(): Number = 2
}

@DependencyGraph
interface MixedGraph {
  @Binds val Int.unusedBinding1: Number
  @Provides fun provideUnusedBinding2(): Number = 2
}

interface InterfaceType

@Inject
@ContributesBinding(AppScope::class)
class Impl1 : InterfaceType

@Inject
@ContributesBinding(AppScope::class)
class Impl2 : InterfaceType

@DependencyGraph(AppScope::class)
interface ContributedGraph

fun box(): String {
  createGraph<BindingGraph>()
  createGraph<ProvidesGraph>()
  createGraph<MixedGraph>()
  createGraph<ContributedGraph>()
  return "OK"
}
