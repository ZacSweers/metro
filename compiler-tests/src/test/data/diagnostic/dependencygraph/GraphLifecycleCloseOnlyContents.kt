// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// METRO_JVM_ONLY

interface Resource
@SingleIn(AppScope::class) @Inject class Database : Resource
@SingleIn(AppScope::class) @Inject class Cache : Resource
@SingleIn(AppScope::class) class Logs : Resource
object Shared : Resource
@AssistedFactory fun interface WorkerFactory { fun create(id: String): Worker }
@AssistedInject class Worker(@Assisted val id: String)

@BindingContainer
interface Bindings {
  @Binds @IntoSet val Database.bindDatabase: Resource
  @Binds @IntoSet val Cache.bindCache: Resource
  @Binds @IntoSet val Shared.bindShared: Resource

  companion object {
    @Provides @IntoSet @SingleIn(AppScope::class) fun logs(): Resource = Logs()
  }
}

// A multibinding is checked through its contributions. Database is used elsewhere, the object holds
// no state, and only close requests Cache and the provided Logs.
@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph : CloseableGraph {
  val database: Database

  @OnGraphClose fun closeAll(<!CLOSE_ONLY_BINDING!>resources: Set<Resource><!>) {}

  // Factories hold no state, so creating one during close isn't reported.
  @OnGraphClose fun stopWorkers(factory: WorkerFactory) {}
}
