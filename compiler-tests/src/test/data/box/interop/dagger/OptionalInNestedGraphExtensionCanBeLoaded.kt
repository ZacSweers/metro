// ENABLE_DAGGER_INTEROP

import java.util.Optional
import kotlin.jvm.optionals.getOrDefault

interface LoggedInScope

interface FeatureScope

interface FeatureOnlyDependency

interface PrivateDependency

interface PrivateFirstDependency

interface PublicFirstDependency

interface QualifiedDependency

interface PrivateExplicitOptionalDependency

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

  val privateFirstOptional: Optional<PrivateFirstDependency>

  val publicFirstOptional: Optional<PublicFirstDependency>

  @Named("mixed") val qualifiedOptional: Optional<QualifiedDependency>

  val privateExplicitOptional: Optional<PrivateExplicitOptionalDependency>
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val featureGraph: FeatureGraph

  val privateFirstOptional: Optional<PrivateFirstDependency>

  val publicFirstOptional: Optional<PublicFirstDependency>

  @Named("mixed") val qualifiedOptional: Optional<QualifiedDependency>

  val privateExplicitOptional: Optional<PrivateExplicitOptionalDependency>
}

@dagger.Module
interface DependencyModule {
  @dagger.BindsOptionalOf fun provideOptional(): LoggedInDependency

  @Named("private")
  @GraphPrivate
  @dagger.BindsOptionalOf
  fun providePrivateOptional(): PrivateDependency
}

@dagger.Module
interface FirstMixedOptionalModule {
  @GraphPrivate
  @dagger.BindsOptionalOf
  fun providePrivateFirstOptional(): PrivateFirstDependency

  @dagger.BindsOptionalOf fun providePublicFirstOptional(): PublicFirstDependency

  @Named("mixed")
  @GraphPrivate
  @dagger.BindsOptionalOf
  fun providePrivateQualifiedOptional(): QualifiedDependency

  @dagger.BindsOptionalOf
  fun provideOptionalWithPrivateExplicitBinding(): PrivateExplicitOptionalDependency
}

@dagger.Module
interface SecondMixedOptionalModule {
  @dagger.BindsOptionalOf fun providePublicSecondOptional(): PrivateFirstDependency

  @GraphPrivate
  @dagger.BindsOptionalOf
  fun providePrivateSecondOptional(): PublicFirstDependency

  @Named("mixed")
  @dagger.BindsOptionalOf
  fun providePublicQualifiedOptional(): QualifiedDependency
}

@DependencyGraph(
  AppScope::class,
  bindingContainers =
    [DependencyModule::class, FirstMixedOptionalModule::class, SecondMixedOptionalModule::class],
)
interface AppGraph {
  val loggedInGraph: LoggedInGraph

  val privateOptionalConsumer: PrivateOptionalConsumer

  @GraphPrivate
  @Provides
  fun providePrivateExplicitOptional(): Optional<PrivateExplicitOptionalDependency> = Optional.empty()
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val loggedInGraph = graph.loggedInGraph
  val featureGraph = loggedInGraph.featureGraph
  assertNotNull(featureGraph.dependency)
  assertTrue(featureGraph.inheritedOptional.isPresent())
  assertTrue(featureGraph.localOptional.isEmpty())
  assertTrue(loggedInGraph.privateFirstOptional.isEmpty())
  assertTrue(loggedInGraph.publicFirstOptional.isEmpty())
  assertTrue(loggedInGraph.qualifiedOptional.isEmpty())
  assertTrue(loggedInGraph.privateExplicitOptional.isEmpty())
  assertTrue(featureGraph.privateFirstOptional.isEmpty())
  assertTrue(featureGraph.publicFirstOptional.isEmpty())
  assertTrue(featureGraph.qualifiedOptional.isEmpty())
  assertTrue(featureGraph.privateExplicitOptional.isEmpty())
  assertTrue(graph.privateOptionalConsumer.optional.isEmpty())
  return "OK"
}
