// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// METRO_JVM_ONLY

interface Plugin
class SharedPlugin : Plugin
class FreshPlugin : Plugin
@Inject class Service
@SingleIn(AppScope::class) @Inject class CloseOnly
interface Included { val label: String }

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphInit fun start(<!UNSCOPED_LIFECYCLE_PARAMETER!>label: String<!>) {}
  @GraphExtension.Factory fun interface Factory { fun create(): Child }
}

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  val service: Service
  val childFactory: Child.Factory

  @Provides @IntoSet @SingleIn(AppScope::class) fun shared(): Plugin = SharedPlugin()
  @Provides @IntoSet fun fresh(): Plugin = FreshPlugin()

  @OnGraphInit
  fun start(
    <!UNSCOPED_LIFECYCLE_PARAMETER!>service: Service<!>,
    <!UNSCOPED_LIFECYCLE_PARAMETER!>plugins: Set<Plugin><!>,
    factory: Child.Factory,
  ) {}

  @OnGraphClose fun stop(<!CLOSE_ONLY_BINDING!>closeOnly: CloseOnly<!>) {}
  @OnGraphInit @OnGraphClose fun run(<!UNSCOPED_LIFECYCLE_PARAMETER!>other: Service<!>) {}

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes included: Included): AppGraph
  }
}
