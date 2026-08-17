// ENABLE_DAGGER_INTEROP

import java.util.Optional
import kotlin.jvm.optionals.getOrDefault

interface LoggedInScope

interface FeatureScope

interface FeatureOnlyDependency

interface PrivateDependency

@Inject
class PrivateOptionalConsumer(@Named("private") val optional: Optional<PrivateDependency>)

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

@dagger.Module
interface FeatureOptionalModule {
  @dagger.BindsOptionalOf fun provideFeatureOptional(): FeatureOnlyDependency
}

@GraphExtension(FeatureScope::class, bindingContainers = [FeatureOptionalModule::class])
interface FeatureGraph {
  val dependency: DelegateDependency

  val inheritedOptional: Optional<LoggedInDependency>

  val localOptional: Optional<FeatureOnlyDependency>
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val featureGraph: FeatureGraph
}

@dagger.Module
interface DependencyModule {
  @dagger.BindsOptionalOf fun provideOptional(): LoggedInDependency

  @Named("private")
  @GraphPrivate
  @dagger.BindsOptionalOf
  fun providePrivateOptional(): PrivateDependency
}

@DependencyGraph(AppScope::class, bindingContainers = [DependencyModule::class])
interface AppGraph {
  val loggedInGraph: LoggedInGraph

  val privateOptionalConsumer: PrivateOptionalConsumer
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val featureGraph = graph.loggedInGraph.featureGraph
  assertNotNull(featureGraph.dependency)
  assertTrue(featureGraph.inheritedOptional.isPresent())
  assertTrue(featureGraph.localOptional.isEmpty())
  assertTrue(graph.privateOptionalConsumer.optional.isEmpty())
  return "OK"
}
