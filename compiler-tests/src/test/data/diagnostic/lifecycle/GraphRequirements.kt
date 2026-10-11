// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!MISSING_CLOSEABLE_GRAPH!>NotCloseableGraph<!> {
  @OnGraphInit fun start() {}
}

@ContributesTo(AppScope::class)
interface ContributedCallbacks {
  @OnGraphClose fun stop() {}
}

@DependencyGraph(AppScope::class) interface <!MISSING_CLOSEABLE_GRAPH!>ContributedCallbackGraph<!>

@DependencyGraph
interface ManualCloseGraph : CloseableGraph {
  override fun <!GRAPH_LIFECYCLE_ERROR!>close<!>() {}
}

interface CloseImplementation : CloseableGraph {
  override fun close() {}
}

@DependencyGraph interface <!GRAPH_LIFECYCLE_ERROR!>InheritedCloseGraph<!> : CloseImplementation

@DependencyGraph interface <!GRAPH_LIFECYCLE_ERROR!>AutoCloseableGraph<!> : AutoCloseable

@DependencyGraph
interface AccessorNamedClose {
  fun close(): String

  @Provides fun provideString(): String = "value"
}
