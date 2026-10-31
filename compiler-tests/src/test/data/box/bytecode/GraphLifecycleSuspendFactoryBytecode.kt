// METRO_JVM_ONLY
// ENABLE_SUSPEND_PROVIDERS

// Init callbacks that don't suspend run in the constructor, so a suspend factory needs no
// metroInit().
@DependencyGraph
interface AppGraph : CloseableGraph {
  @OnGraphInit fun start() {}

  @DependencyGraph.Factory fun interface Factory { suspend fun create(): AppGraph }
}

fun box(): String = runBlocking {
  createGraphFactory<AppGraph.Factory>().create()
  "OK"
}

// CHECK_BYTECODE_TEXT
// @AppGraph$Impl.class:
// 0 metroInit
