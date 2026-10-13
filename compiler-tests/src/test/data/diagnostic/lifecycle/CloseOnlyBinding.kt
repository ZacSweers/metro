// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@SingleIn(AppScope::class) @Inject class Database

@SingleIn(AppScope::class) @Inject class Cache

@SingleIn(AppScope::class) @Inject class Repository(val database: () -> SharedDatabase)

@SingleIn(AppScope::class) @Inject class SharedDatabase

@SingleIn(AppScope::class) @Inject class ChildDatabase

@GraphExtension
interface ChildGraph {
  val database: ChildDatabase
}

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  val repository: Repository
  val child: ChildGraph

  @OnGraphInit fun start(cache: Lazy<Cache>) {}

  @OnGraphClose fun closeDatabase(<!CLOSE_ONLY_BINDING!>database: Database<!>) {}

  @OnGraphClose fun closeCache(cache: Cache) {}

  @OnGraphClose fun closeShared(database: SharedDatabase) {}

  @OnGraphClose fun closeChildDatabase(database: ChildDatabase) {}

  @Suppress("CLOSE_ONLY_BINDING") @OnGraphClose fun closeSuppressed(database: Database) {}

  @OnGraphClose fun closeInput(input: String) {}

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides input: String): AppGraph
  }
}
