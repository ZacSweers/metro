// WITH_RUNTIME_COROUTINES

interface Callbacks {
  @OnGraphInit fun <!DEPENDENCY_GRAPH_ERROR!>missingBody<!>()
  @OnGraphClose fun <!DEPENDENCY_GRAPH_ERROR!>wrongReturn<!>(): Int = 1
  @OnGraphInit fun startup() {}
}

interface SubCallbacks : Callbacks {
  override fun <!DEPENDENCY_GRAPH_ERROR!>startup<!>() {}
}

@DependencyGraph interface <!DEPENDENCY_GRAPH_ERROR!>Manual<!> : CloseableGraph {
  @OnGraphClose override fun close() {}
}

@DependencyGraph interface SyncCallbacks : CloseableGraph {
  @OnGraphInit suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>start<!>() {}
}

@DependencyGraph interface <!SUSPEND_PROVIDERS_NOT_ENABLED!>SuspendCleanup<!> : SuspendCloseableGraph

@DependencyGraph interface SuspendFactoryOnly : CloseableGraph {
  @DependencyGraph.Factory fun interface Factory {
    suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>create<!>(): SuspendFactoryOnly
  }
}
