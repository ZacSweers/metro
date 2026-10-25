// RUN_PIPELINE_TILL: FRONTEND

@AssistedInject class Worker(@Assisted val id: String)
@AssistedFactory fun interface WorkerFactory { fun create(id: String): Worker }

// Callback parameters get the same injection site checks as accessors.
interface Callbacks {
  @OnGraphInit fun start(factory: <!ASSISTED_FACTORIES_CANNOT_BE_LAZY!>Lazy<WorkerFactory><!>) {}
}
