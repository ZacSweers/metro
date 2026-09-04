// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Origin
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.internal.MetroContribution
import kotlin.reflect.KClass

/** An ordinary object is an implicit class binding. */
object LibRegistry

interface LibJson

/** A binary graph supertype: its accessors and providers merge into graphs extending it. */
interface LibBaseGraph {
  val libJson: LibJson

  @Provides fun provideLibJson(): LibJson = object : LibJson {}
}

/** Specializes inherited binary providers separately for every concrete graph declaration. */
interface LibGenericBase<T> {
  @Provides fun provideValue(): T = error("unused")
}

/** Isolates contributed interface members from unrelated fixture graphs. */
abstract class LibInterfaceScope

@Inject class LibInterfaceDependency

@Inject class LibInterfaceClient(val dependency: LibInterfaceDependency)

@HasMemberInjections
class LibInterfaceMembers {
  @Inject lateinit var client: LibInterfaceClient
}

/** Inherited members use the concrete type supplied by the contributed interface. */
interface LibContributedGeneric<T> {
  val value: T

  @Provides fun provideValue(dependency: LibInterfaceDependency): T = error("unused")

  fun inject(target: LibInterfaceMembers)
}

@ContributesTo(LibInterfaceScope::class)
interface LibContributedGraph : LibContributedGeneric<String> {
  val client: LibInterfaceClient
}

interface LibDefaultValue

interface LibDefaultGraphBase {
  val defaultValue: LibDefaultValue
}

/** A contributed default implementation satisfies its inherited abstract accessor. */
@ContributesTo(LibInterfaceScope::class)
interface LibDefaultGraph : LibDefaultGraphBase {
  override val defaultValue: LibDefaultValue
    get() = error("unused")
}

/** Only an internal contribution hint exposes this interface. */
@ContributesTo(LibInterfaceScope::class)
interface LibHiddenGraph {
  val hidden: LibDefaultValue
}

interface LibService

interface LibAnalytics

/** Exercises annotation defaults when the qualifier declaration is only available as a binary. */
@Qualifier annotation class LibEndpoint(val name: String = "main", val version: Int = 1)

/** Resolvable on demand as a constructor-injected library class. */
@Inject @SingleIn(AppScope::class) class LibHttpClient

/** Each dependency adds a type argument layer during implicit construction. */
@Inject class LibGrowingNode<T>(val next: LibGrowingNode<List<T>>)

/** Carries constructor dependencies, for binary dependency-key extraction. */
@Inject class LibClientWithDeps(val client: LibHttpClient)

/** Exercises transitive dependencies discovered from a binary contribution hint. */
interface LibTransitiveService

@Inject
@ContributesBinding(AppScope::class)
class LibTransitiveServiceImpl(val dependency: LibClientWithDeps) : LibTransitiveService

/** Binary assisted factories expose their target's non-assisted, transitive dependencies. */
@AssistedInject
class LibAssistedWidget(@Assisted val id: String, val dependency: LibClientWithDeps)

@AssistedFactory
interface LibAssistedWidgetFactory {
  fun create(id: String): LibAssistedWidget
}

/** Mirrors the compiler's cross-module assisted factories with concrete type substitutions. */
@AssistedInject
class LibGenericAssistedExample<T>(@Assisted val inputT: T, val graphT: T) {
  @AssistedFactory
  fun interface Factory<T> {
    fun create(inputT: T): LibGenericAssistedExample<T>
  }

  @AssistedFactory
  fun interface Factory2 {
    fun create(inputT: Int): LibGenericAssistedExample<Int>
  }
}

/** The assisted input and the graph dependency can resolve to different concrete types. */
@AssistedInject
class LibGenericAssistedDifferent<T, R>(@Assisted val inputT: T, val graphT: R) {
  @AssistedFactory
  fun interface Factory<T, R> {
    fun create(inputT: T): LibGenericAssistedDifferent<T, R>
  }

  @AssistedFactory
  fun interface Factory2<T> {
    fun create(inputT: Int): LibGenericAssistedDifferent<Int, T>
  }
}

interface LibGenericAssistedBaseFactory<T> {
  fun create(inputT: T): LibGenericAssistedExample<T>
}

/** Binary inherited factory functions must retain the requested factory's type argument. */
@AssistedFactory
interface LibInheritedGenericAssistedFactory<T> : LibGenericAssistedBaseFactory<T>

/** Deferred wrappers must keep both the concrete graph key and their provider semantics. */
@AssistedInject
class LibWrappedGenericAssisted<T>(@Assisted val inputT: T, val graphT: Provider<T>) {
  @AssistedFactory
  fun interface Factory<T> {
    fun create(inputT: T): LibWrappedGenericAssisted<T>
  }
}

/** Suspend factory functions also resolve their target's generic graph dependency concretely. */
@AssistedInject
class LibSuspendGenericAssisted<T>(@Assisted val id: String, val graphT: T) {
  @AssistedFactory
  interface Factory<T> {
    suspend fun create(id: String): LibSuspendGenericAssisted<T>
  }
}

/** Qualifiers apply to the specialized graph dependency, not the assisted factory argument. */
@AssistedInject
class LibQualifiedGenericAssisted<T>(
  @Assisted val inputT: T,
  @LibEndpoint("primary") val graphT: T,
) {
  @AssistedFactory
  fun interface Factory<T> {
    fun create(inputT: T): LibQualifiedGenericAssisted<T>
  }
}

@AssistedInject
class LibRetargetedWidgetA(@Assisted val id: String, val dependency: LibRetargetedDependencyA)

@AssistedInject
class LibRetargetedWidgetB(@Assisted val id: String, val dependency: LibRetargetedDependencyB)

@Inject class LibRetargetedDependencyA

@Inject class LibRetargetedDependencyB(val dependency: LibClientWithDeps)

/** Resolvable on demand only under its qualifier. */
@Inject @LibEndpoint("primary") class LibQualifiedClient

@Inject @ContributesBinding(AppScope::class) class LibServiceImpl : LibService

@Inject @ContributesIntoSet(AppScope::class) class LibAnalyticsImpl : LibAnalytics

// Explicit binding<T>() — the bound type is unrecoverable from supertypes alone, and binaries
// don't carry the annotation's type argument. Real Metro-compiled libraries expose it through a
// generated nested MetroContribution interface with @Binds members, replicated here by hand.
interface LibExplicit

interface LibMarker

/** Mirrors Anvil's rank-bearing contribution annotation in compiled dependency metadata. */
annotation class LibRankedBinding(val scope: KClass<*>, val rank: Int)

/** Mirrors Metro's first-party priority without depending on the bootstrap runtime version. */
annotation class LibPrioritizedBinding(val scope: KClass<*>, val priority: Int = Int.MIN_VALUE)

/** Exposes the explicit map value type structurally in compiled Kotlin metadata. */
annotation class LibPrioritizedMapBinding(
  val scope: KClass<*>,
  val boundType: KClass<*>,
  val priority: Int = Int.MIN_VALUE,
)

/** Mirrors custom annotations that contribute to sets or prioritized maps. */
annotation class LibCustomMultibinding(
  val scope: KClass<*>,
  val priority: Int = Int.MIN_VALUE,
  val ignoreQualifier: Boolean = false,
)

/** Mirrors Kotlin Inject Anvil's repeatable ordinary-or-set contribution annotation. */
@Repeatable
annotation class LibMixedMultibindingBinding(
  val scope: KClass<*>,
  val multibinding: Boolean = false,
  val priority: Int = Int.MIN_VALUE,
)

interface LibRankedService

@Inject
@StringKey("shared")
@LibRankedBinding(AppScope::class, rank = 50)
@LibPrioritizedBinding(AppScope::class, priority = 50)
@LibPrioritizedMapBinding(AppScope::class, boundType = LibRankedService::class, priority = 50)
class LibLowerRankedService : LibRankedService, LibMarker {
  interface MetroContributionToAppScope {
    @Binds val LibLowerRankedService.bindLibRankedService: LibRankedService
  }
}

@Inject
@StringKey("shared")
@LibRankedBinding(AppScope::class, rank = 100)
@LibPrioritizedBinding(AppScope::class, priority = 100)
@LibPrioritizedMapBinding(AppScope::class, boundType = LibRankedService::class, priority = 100)
class LibHigherRankedService : LibRankedService, LibMarker {
  interface MetroContributionToAppScope {
    @Binds val LibHigherRankedService.bindLibRankedService: LibRankedService
  }
}

@Inject
@ContributesBinding(AppScope::class, binding = binding<LibExplicit>())
class LibExplicitImpl : LibExplicit, LibMarker {
  interface MetroContributionToAppScope {
    @Binds val LibExplicitImpl.bindLibExplicit: LibExplicit
  }
}

// Replicates the compiler's generate-contribution-providers output: a holder class with a
// per-scope container object carrying @Origin and the actual @Provides members, letting the
// implementation stay internal.
interface LibContained

@LibPrioritizedBinding(AppScope::class, priority = 25)
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

/** Keeps generated multibinding fixture members out of unrelated AppScope library scans. */
abstract class LibMultibindingScope

interface LibSetService

@Inject
@LibCustomMultibinding(LibMultibindingScope::class)
class LibFirstSetService : LibSetService, LibMarker {
  interface MetroContributionToMultibindingScope {
    @Binds @IntoSet
    val LibFirstSetService.bindLibSetService: LibSetService
  }
}

@Inject
@LibCustomMultibinding(LibMultibindingScope::class)
class LibSecondSetService : LibSetService, LibMarker {
  interface MetroContributionToMultibindingScope {
    @Binds @IntoSet
    val LibSecondSetService.bindLibSetService: LibSetService
  }
}

interface LibIgnoreQualifierSetService

@Inject
@LibEndpoint("ignored")
@LibCustomMultibinding(LibMultibindingScope::class, ignoreQualifier = true)
class LibIgnoreQualifierSetServiceImpl : LibIgnoreQualifierSetService

interface LibMixedMultibindingService

@Inject
@LibMixedMultibindingBinding(LibMultibindingScope::class, priority = 5)
@LibMixedMultibindingBinding(LibMultibindingScope::class, multibinding = true)
class LibMixedMultibindingServiceImpl : LibMixedMultibindingService, LibMarker {
  interface MetroContributionToMultibindingScope {
    @Binds
    val LibMixedMultibindingServiceImpl.bindLibMixedService: LibMixedMultibindingService

    @Binds @IntoSet
    val LibMixedMultibindingServiceImpl.bindLibMixedSetService: LibMixedMultibindingService
  }
}

@Inject
@LibMixedMultibindingBinding(LibMultibindingScope::class, multibinding = true)
class LibOtherMixedSetService : LibMixedMultibindingService, LibMarker {
  interface MetroContributionToMultibindingScope {
    @Binds @IntoSet
    val LibOtherMixedSetService.bindLibMixedSetService: LibMixedMultibindingService
  }
}

interface LibPrioritizedCustomMapService

@Inject
@StringKey("shared")
@LibCustomMultibinding(LibMultibindingScope::class, priority = 50)
class LibLowerPriorityCustomMapService : LibPrioritizedCustomMapService, LibMarker {
  interface MetroContributionToMultibindingScope {
    @Binds @IntoMap @StringKey("shared")
    val LibLowerPriorityCustomMapService.bindLibPrioritizedCustomMapService:
      LibPrioritizedCustomMapService
  }
}

@Inject
@StringKey("shared")
@LibCustomMultibinding(LibMultibindingScope::class, priority = 100)
class LibHigherPriorityCustomMapService : LibPrioritizedCustomMapService, LibMarker {
  interface MetroContributionToMultibindingScope {
    @Binds @IntoMap @StringKey("shared")
    val LibHigherPriorityCustomMapService.bindLibPrioritizedCustomMapService:
      LibPrioritizedCustomMapService
  }
}

interface LibContainedSetService

@LibCustomMultibinding(LibMultibindingScope::class)
internal class LibContainedSetImpl : LibContainedSetService

abstract class LibContainedSetImplContributions {
  @MetroContribution(LibMultibindingScope::class)
  @Origin(LibContainedSetImpl::class, context = "contribution_provider")
  @BindingContainer
  @ContributesTo(LibMultibindingScope::class)
  object ToMultibindingScope {
    @Provides @IntoSet
    fun provideLibContainedSet(): LibContainedSetService = LibContainedSetImpl()
  }
}

abstract class LibScope

interface LibDual

internal class LibDualImpl : LibDual

abstract class LibDualImplContributions {
  @Origin(LibDualImpl::class, context = "contribution_provider")
  @BindingContainer
  object ToScopes {
    @Provides fun provideLibDual(): LibDual = LibDualImpl()
  }
}

// Contributed only via an internal hint, which consuming modules must not see
interface LibHidden

@Inject @ContributesBinding(AppScope::class) class LibHiddenImpl : LibHidden
