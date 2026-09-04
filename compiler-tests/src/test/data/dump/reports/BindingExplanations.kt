// RUN_PIPELINE_TILL: BACKEND
// ENABLE_DAGGER_INTEROP
// Generated child accessors have different source offsets across Kotlin versions.
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-AppGraph-Impl-ChildGraphImpl.json

import dagger.BindsOptionalOf
import java.util.Optional

interface Service

@Inject
@ContributesBinding(AppScope::class)
class OriginalService : Service

@Inject
@ContributesBinding(AppScope::class, replaces = [OriginalService::class])
class ReplacementService : Service

interface PrioritizedService

@Inject
@ContributesBinding(AppScope::class, priority = 1)
class LowPriorityService : PrioritizedService

@Inject
@ContributesBinding(AppScope::class, priority = 10)
class HighPriorityService : PrioritizedService

interface Handler

@Inject
@ContributesIntoSet(AppScope::class)
class KeptHandler : Handler

@Inject
@ContributesIntoSet(AppScope::class)
class ExcludedHandler : Handler

@Inject class ExplicitValue

@Inject class DefaultValue(val number: Int = 42)

@Inject
class MemberTarget {
  @Inject lateinit var <!MEMBERS_INJECT_WARNING!>text<!>: String
}

@BindingContainer
interface OptionalBindings {
  @BindsOptionalOf fun optionalString(): String
}

@MergeContributionsInIr
@DependencyGraph(
  AppScope::class,
  bindingContainers = [OptionalBindings::class],
  excludes = [ExcludedHandler::class],
)
interface AppGraph {
  val service: Service
  val prioritizedService: PrioritizedService
  val handlers: Set<Handler>
  val explicitValue: ExplicitValue
  val defaultValue: DefaultValue
  val optionalString: Optional<String>
  val memberTarget: MemberTarget
  val child: ChildGraph
  val firstMapConsumer: FirstMapConsumer
  val mapConsumerHolder: MapConsumerHolder

  @Provides fun <!REDUNDANT_PROVIDES!>explicitValue<!>(): ExplicitValue = ExplicitValue()

  @Provides fun text(): String = "text"

  @Provides fun directMap(): Map<String, Int> = mapOf("direct" to 1)

  @Provides @IntoMap @StringKey("contributed") fun mapValue(): Int = 2
}

@Inject class FirstMapConsumer(val values: Map<String, () -> Int>)

@Inject class SecondMapConsumer(val values: Map<String, () -> Int>)

// The holder lets the second request reuse a map resolved earlier in the traversal.
@Inject class MapConsumerHolder(val consumer: SecondMapConsumer)

// The parent value is discovered through the child's constructor dependencies.
@SingleIn(AppScope::class) @Inject class ParentValue

@Inject class FirstParentConsumer(val parentValue: ParentValue)

@Inject class SecondParentConsumer(val parentValue: ParentValue)

@GraphExtension
interface ChildGraph {
  val first: FirstParentConsumer
  val second: SecondParentConsumer
}
