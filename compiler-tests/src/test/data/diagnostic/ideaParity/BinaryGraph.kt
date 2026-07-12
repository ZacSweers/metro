// RUN_PIPELINE_TILL: BACKEND
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// CHECK_REPORTS: graph-metadata/graph-parity-binary-BinaryGraph.json
// CHECK_REPORTS: keys-populated/parity/binary/BinaryGraph/Impl
// CHECK_REPORTS: keys-validated/parity/binary/BinaryGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/binary/BinaryGraph/Impl
// METRO_JVM_ONLY
// MODULE: lib
// FILE: LibFixtures.kt
package libtest

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Origin
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.internal.MetroContribution

interface LibService

interface LibAnalytics

@Inject @SingleIn(AppScope::class) class LibHttpClient

@Inject class LibClientWithDeps(val client: LibHttpClient)

@Inject @ContributesBinding(AppScope::class) class LibServiceImpl : LibService

@Inject @ContributesIntoSet(AppScope::class) class LibAnalyticsImpl : LibAnalytics

interface LibExplicit

interface LibMarker

@Inject
@ContributesBinding(AppScope::class, binding = binding<LibExplicit>())
class LibExplicitImpl : LibExplicit, LibMarker

interface LibContained

internal class LibContainedImpl : LibContained

abstract class LibContainedImplContributions {
  @MetroContribution(AppScope::class)
  @Origin(LibContainedImpl::class, context = "contribution_provider")
  @BindingContainer
  @ContributesTo(AppScope::class)
  object ToAppScope {
    @Provides fun provideLibContained(): LibContained = LibContainedImpl()
  }
}

interface LibHidden

@Inject @ContributesBinding(AppScope::class) internal class LibHiddenImpl : LibHidden

// FILE: hints.kt
@file:Suppress("unused", "FunctionName")

package metro.hints

import libtest.LibContainedImplContributions

fun dev_zacsweers_metro_AppScope(contributed: LibContainedImplContributions.ToAppScope) {}

// MODULE: main(lib)
// FILE: BinaryGraph.kt
package parity.binary

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.OptionalBinding
import libtest.*

@DependencyGraph(AppScope::class)
interface BinaryGraph {
  val client: LibClientWithDeps
  val service: LibService
  val analytics: Set<LibAnalytics>
  val explicit: LibExplicit
  val contained: LibContained

  @OptionalBinding
  val hidden: LibHidden?
    get() = null
}
