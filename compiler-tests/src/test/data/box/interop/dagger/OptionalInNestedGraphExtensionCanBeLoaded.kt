// ENABLE_DAGGER_INTEROP

import java.util.Optional
import kotlin.jvm.optionals.getOrDefault

interface LoggedInScope

interface FeatureScope

interface DelegateDependency

@ContributesBinding(AppScope::class)
@Inject
class DelegateDependencyImpl(
  private val appDependency: AppDependency,
  private val optionalDep: Optional<LoggedInDependency>,
) : DelegateDependency by optionalDep.getOrDefault(appDependency)

interface AppDependency : DelegateDependency

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AppDependencyImpl : AppDependency

interface LoggedInDependency : DelegateDependency

@ContributesBinding(LoggedInScope::class)
@SingleIn(LoggedInScope::class)
@Inject
class LoggedInDependencyImpl : LoggedInDependency

@GraphExtension(FeatureScope::class)
interface FeatureGraph {
  val dependency: DelegateDependency
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val featureGraph: FeatureGraph
}

@dagger.Module
interface DependencyModule {
  @dagger.BindsOptionalOf fun provideOptional(): LoggedInDependency
}

@DependencyGraph(AppScope::class, bindingContainers = [DependencyModule::class])
interface AppGraph {
  val loggedInGraph: LoggedInGraph
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.loggedInGraph.featureGraph.dependency)
  return "OK"
}
