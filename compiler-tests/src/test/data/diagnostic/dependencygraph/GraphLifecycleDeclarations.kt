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
  @OnGraphInit suspend fun <!DEPENDENCY_GRAPH_ERROR, SUSPEND_PROVIDERS_NOT_ENABLED!>start<!>() {}
}

@DependencyGraph interface <!SUSPEND_PROVIDERS_NOT_ENABLED!>SuspendCleanup<!> : SuspendCloseableGraph

@DependencyGraph interface SuspendFactoryOnly : CloseableGraph {
  @DependencyGraph.Factory fun interface Factory {
    suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>create<!>(): SuspendFactoryOnly
  }
}

abstract class Shapes {
  @OnGraphInit private fun <!DEPENDENCY_GRAPH_ERROR!>hidden<!>() {}
  @OnGraphInit fun String.<!DEPENDENCY_GRAPH_ERROR!>withReceiver<!>() {}
  context(value: String)
  @OnGraphInit fun <!DEPENDENCY_GRAPH_ERROR!>withContext<!>() {}
  @OnGraphInit fun <T> <!DEPENDENCY_GRAPH_ERROR!>generic<!>() {}
}

interface SuspendHooks {
  @OnGraphInit suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>first<!>() {}
  @OnGraphInit suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>second<!>() {}
}

@DependencyGraph interface <!SUSPEND_PROVIDERS_NOT_ENABLED!>InheritsSuspendHooks<!> : CloseableGraph, SuspendHooks {
  @DependencyGraph.Factory fun interface Factory {
    suspend fun <!SUSPEND_PROVIDERS_NOT_ENABLED!>create<!>(): InheritsSuspendHooks
  }
}
