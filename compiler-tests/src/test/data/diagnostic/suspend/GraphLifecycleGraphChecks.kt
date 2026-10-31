// ENABLE_SUSPEND_PROVIDERS

class Scope

@ContributesTo(Scope::class)
interface Hooks {
  @OnGraphInit fun start() {}
}

@DependencyGraph(Scope::class) interface <!DEPENDENCY_GRAPH_ERROR!>NotCloseable<!>

@DependencyGraph interface SyncClose : CloseableGraph {
  @OnGraphClose suspend fun <!DEPENDENCY_GRAPH_ERROR!>stop<!>() {}
}

@DependencyGraph interface SyncFactory : CloseableGraph {
  @OnGraphInit suspend fun <!DEPENDENCY_GRAPH_ERROR!>start<!>() {}
  @DependencyGraph.Factory fun interface Factory { fun create(): SyncFactory }
}

@DependencyGraph interface SuspendFactory : CloseableGraph {
  @OnGraphInit suspend fun start() {}
  @DependencyGraph.Factory fun interface Factory { suspend fun create(): SuspendFactory }
}

interface GenericHooks<T> {
  @OnGraphInit suspend fun start(value: T) {}
}

@DependencyGraph interface <!DEPENDENCY_GRAPH_ERROR!>GenericInit<!> : CloseableGraph, GenericHooks<String> {
  @Provides fun value(): String = "value"
}
