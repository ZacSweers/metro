// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@Inject class Service

@SingleIn(AppScope::class) @Inject class ScopedService

interface Plugin

@Inject class UnscopedPlugin : Plugin

@SingleIn(AppScope::class) @Inject class ScopedPlugin : Plugin

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  @OnGraphInit fun initializeService(<!UNSCOPED_LIFECYCLE_PARAMETER!>service: Service<!>) {}

  @OnGraphInit fun deferred(provider: () -> Service, lazy: Lazy<Service>) {}

  @OnGraphInit fun scoped(service: ScopedService, input: String, graph: AppGraph) {}

  @Suppress("UNSCOPED_LIFECYCLE_PARAMETER") @OnGraphInit fun suppressed(service: Service) {}

  @OnGraphClose fun closePlugins(<!UNSCOPED_LIFECYCLE_PARAMETER!>plugins: Set<Plugin><!>) {}

  @Binds @IntoSet val UnscopedPlugin.bindUnscoped: Plugin

  @Binds @IntoSet val ScopedPlugin.bindScoped: Plugin

  val plugins: Set<Plugin>

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides input: String): AppGraph
  }
}
