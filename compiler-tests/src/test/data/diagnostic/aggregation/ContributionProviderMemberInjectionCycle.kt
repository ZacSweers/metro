// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

interface Service

@Inject
@ContributesBinding(AppScope::class)
class ServiceImpl : Service {
  private var consumer: Consumer? = null

  @Inject
  fun initialize(consumer: Consumer) {
    this.consumer = consumer
  }
}

@Inject class Consumer(val service: Service)

@DependencyGraph(AppScope::class)
interface <!GRAPH_DEPENDENCY_CYCLE!>AppGraph<!> {
  val service: Service
}
