// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-aggregation-AppGraph.json
// CHECK_REPORTS: keys-populated/parity/aggregation/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/aggregation/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/aggregation/AppGraph/Impl

package parity.aggregation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey

abstract class OtherScope private constructor()

interface Repo

@Inject
@ContributesBinding(AppScope::class)
class RealRepo : Repo

@Inject
@ContributesBinding(AppScope::class, replaces = [RealRepo::class])
class TestRepo(val realRepo: RealRepo) : Repo

interface Handler

@Inject
@ContributesIntoSet(AppScope::class)
class AppHandler : Handler

@Inject
@ContributesIntoSet(OtherScope::class)
class OtherHandler : Handler

@Inject
@ContributesIntoSet(AppScope::class)
class ExcludedHandler : Handler

interface EmptyElement

@BindingContainer
interface AggregateBindings {
  @Multibinds(allowEmpty = true) fun emptyElements(): Set<EmptyElement>

  companion object {
    @Provides @IntoMap @StringKey("primary") fun primaryHandler(repo: Repo): Handler =
      object : Handler {}

    @Provides @IntoMap @StringKey("secondary") fun secondaryHandler(): Handler =
      object : Handler {}
  }
}

@SingleIn(AppScope::class)
@Inject
class ScopedValue

@DependencyGraph(
  AppScope::class,
  bindingContainers = [AggregateBindings::class],
  excludes = [ExcludedHandler::class],
)
interface AppGraph {
  val repo: Repo
  val handlers: Set<Handler>
  val handlersByName: Map<String, Handler>
  val emptyElements: Set<EmptyElement>
  val scopedValue: ScopedValue
}

// METRO_JVM_ONLY
