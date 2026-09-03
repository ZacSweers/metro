// RUN_PIPELINE_TILL: BACKEND
// ENABLE_DAGGER_INTEROP
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json

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

  @Provides fun <!REDUNDANT_PROVIDES!>explicitValue<!>(): ExplicitValue = ExplicitValue()

  @Provides fun text(): String = "text"
}
