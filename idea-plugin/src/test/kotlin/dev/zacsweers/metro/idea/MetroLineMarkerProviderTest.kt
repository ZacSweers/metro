// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import kotlin.test.assertTrue
import org.jetbrains.kotlin.psi.KtFile

class MetroLineMarkerProviderTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    project.service<MetroGraphValidationService>().clearResults()
    project.service<GraphContextPinService>().clear()
  }

  /** Waits for the index and file bundle before running the platform's highlighting pass. */
  private fun highlightMetroFile() {
    val file = myFixture.file as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    if (index !== BindingIndex.EMPTY) file.awaitMetroPresentation()
    myFixture.doHighlighting()
  }

  private fun configureAndHighlight(): List<String> {
    myFixture.configureByText(
      "Test.kt",
      """
      package test

      import dev.zacsweers.metro.AppScope
      import dev.zacsweers.metro.Assisted
      import dev.zacsweers.metro.AssistedInject
      import dev.zacsweers.metro.Binds
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.Inject

      interface Service
      @Inject class ServiceImpl : Service

      interface ServiceBindings {
        @Binds fun bindService(impl: ServiceImpl): Service
      }

      @Inject
      class Consumer(
        val service: Service,
        val missing: Long,
      )

      @AssistedInject class Presenter(@Assisted val id: String, val service: Service)

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val consumer: Consumer
      }
      """
        .trimIndent(),
    )
    highlightMetroFile()
    val metroIcons =
      setOf(
        MetroIcons.PROVIDER,
        MetroIcons.CONSUMER,
        MetroIcons.CONSUMER_UNRESOLVED,
        MetroIcons.CONSUMER_ASSISTED,
        MetroIcons.GRAPH,
        MetroIcons.GRAPH_VALIDATED,
        MetroIcons.GRAPH_PROBLEMS,
        MetroIcons.CONTRIBUTED,
      )
    return myFixture.findAllGutters().filter { it.icon in metroIcons }.mapNotNull { it.tooltipText }
  }

  fun testInjectorMarkerTargetsInjectedMembers() {
    myFixture.configureMetroFile(
      """
      interface Api
      interface Tracker

      class Target {
        @Inject lateinit var api: Api
        @Inject lateinit var tracker: Tracker
      }

      @DependencyGraph
      interface AppGraph {
        fun inject(target: Target)

        @Provides fun provideApi(): Api = object : Api {}
        @Provides fun provideTracker(): Tracker = object : Tracker {}
      }
      """
    )
    highlightMetroFile()
    val tooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.CONSUMER || it.icon === MetroIcons.CONSUMER_UNRESOLVED }
        .mapNotNull { it.tooltipText }
    assertTrue("Expected an injector marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro injector: injects 2 dependencies into Target") }
    }
  }

  fun testGenericProviderParameterKeepsItsConcreteConsumerMarker() {
    myFixture.configureMetroFile(
      """
      @Inject class Dependency

      interface GenericBase<T> {
        @Provides fun provideText(value: T): String = value.toString()
      }

      @DependencyGraph
      interface AppGraph : GenericBase<Dependency> {
        val text: String
      }
      """
    )
    highlightMetroFile()
    val tooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.CONSUMER }
        .mapNotNull { it.tooltipText }

    assertTrue("Expected a concrete provider-parameter consumer in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro dependency: Dependency") }
    }
    assertTrue("The raw generic parameter must not appear as a dependency:\n$tooltips") {
      tooltips.none { it.startsWith("Metro dependency: T") }
    }
  }

  fun testMultipleGenericProviderSpecializationsAreNotInjectorMarkers() {
    myFixture.configureMetroFile(
      """
      interface GenericBase<T> {
        @Provides fun provideText(value: T): String = value.toString()
      }

      @DependencyGraph
      interface IntGraph : GenericBase<Int> {
        val text: String

        @Provides fun provideInt(): Int = 1
      }

      @DependencyGraph
      interface BooleanGraph : GenericBase<Boolean> {
        val text: String

        @Provides fun provideBoolean(): Boolean = true
      }
      """
    )
    highlightMetroFile()
    val tooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.CONSUMER || it.icon === MetroIcons.CONSUMER_UNRESOLVED }
        .mapNotNull { it.tooltipText }

    assertTrue("Expected a context-dependent provider-parameter consumer in:\n$tooltips") {
      tooltips.any { "Metro dependency: Int / Boolean" in it && "2 graph contexts" in it }
    }
    assertTrue("A generic provider parameter is not a graph injector:\n$tooltips") {
      tooltips.none { it.startsWith("Metro injector:") }
    }
  }

  fun testMatchingGenericSpecializationsUseAnHonestConsumerTooltip() {
    myFixture.configureMetroFile(
      """
      interface Api

      @Inject @ContributesBinding(AppScope::class)
      class RealApi : Api

      interface GenericBase<T> {
        @Provides fun provideText(value: T): String = value.toString()
      }

      @DependencyGraph(AppScope::class)
      interface FirstGraph : GenericBase<Api> {
        val text: String
      }

      @DependencyGraph(AppScope::class)
      interface SecondGraph : GenericBase<Api> {
        val text: String
      }
      """
    )
    highlightMetroFile()
    val tooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.CONSUMER }
        .mapNotNull { it.tooltipText }

    assertTrue("Expected a shared implementation tooltip in:\n$tooltips") {
      tooltips.any { "Metro dependency: Api · available in 2 graph contexts" in it }
    }
    assertTrue("Matching implementations must not be described as different:\n$tooltips") {
      tooltips.none { "Metro dependency: Api · bindings differ" in it }
    }
  }

  fun testDifferentAliasTargetsUseContextDependentConsumerTooltips() {
    myFixture.configureMetroFile(
      """
      interface Api
      @Inject class RealA : Api
      @Inject class RealB : Api

      interface GenericBindings<T : Api> {
        @Binds fun bindApi(value: T): Api

        @Provides fun provideText(value: Api): String = value.toString()
      }

      interface GenericConsumers<T> {
        @Provides fun provideCount(value: T): Int = value.hashCode()
      }

      @DependencyGraph
      interface FirstGraph : GenericBindings<RealA>, GenericConsumers<Api> {
        val text: String
        val count: Int
      }

      @DependencyGraph
      interface SecondGraph : GenericBindings<RealB>, GenericConsumers<Api> {
        val text: String
        val count: Int
      }
      """
    )
    highlightMetroFile()
    val tooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.CONSUMER }
        .mapNotNull { it.tooltipText }
        .filter { it.startsWith("Metro dependency: Api") }

    // Both the ordinary Api parameter and the generic parameter specialized to Api must agree.
    assertEquals(2, tooltips.size)
    assertTrue("Different alias targets must stay context-dependent:\n$tooltips") {
      tooltips.all { "bindings differ across 2 graph contexts" in it }
    }
  }

  fun testValidateMarkerBadgesValidationState() {
    val file =
      myFixture.configureMetroFile(
        """
        interface MissingThing

        @DependencyGraph
        interface AppGraph {
          val missing: MissingThing
        }
        """
      )
    highlightMetroFile()
    fun validateIcons() =
      myFixture
        .findAllGutters()
        .map { it.icon }
        .filter {
          it === MetroIcons.GRAPH ||
            it === MetroIcons.GRAPH_VALIDATED ||
            it === MetroIcons.GRAPH_PROBLEMS
        }
    // Not validated yet: plain graph icon
    assertEquals(listOf<Any>(MetroIcons.GRAPH), validateIcons())

    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single()
    project.service<MetroGraphValidationService>().validate(file, index.contextsFor(graph).single())
    // The file didn't change, so mimic production's post-validation daemon restart
    DaemonCodeAnalyzer.getInstance(project).restart()
    highlightMetroFile()
    assertEquals(listOf<Any>(MetroIcons.GRAPH_PROBLEMS), validateIcons())
    val tooltip =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.GRAPH_PROBLEMS }
        .mapNotNull { it.tooltipText }
        .single()
    assertTrue(tooltip, "last run: 1 problem" in tooltip)
  }

  fun testIncompleteValidationDoesNotShowASuccessfulGraphBadge() {
    module.addKotlinStdlibLibrary()
    val file =
      myFixture.configureMetroFile(
        """
        @AssistedInject
        class Node<T>(@Assisted val id: String, val next: Node.Factory<List<T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Node.Factory<Int>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val validationService = project.service<MetroGraphValidationService>()
    val result = validationService.validate(file, context)
    assertTrue(result is KaGraphValidationResult.Incomplete)
    result as KaGraphValidationResult.Incomplete
    assertSame(result, validationService.validate(file, context))

    DaemonCodeAnalyzer.getInstance(project).restart()
    highlightMetroFile()
    val gutters = myFixture.findAllGutters()
    assertTrue(gutters.none { it.icon === MetroIcons.GRAPH_VALIDATED })
    val tooltip =
      gutters
        .filter { it.icon === MetroIcons.GRAPH_PROBLEMS }
        .mapNotNull { it.tooltipText }
        .single()
    assertTrue(tooltip, "last run: analysis incomplete" in tooltip)
    assertTrue(tooltip, result.reason in tooltip)
    assertTrue(tooltip, "no problems found" !in tooltip)
    assertTrue(tooltip, "internal Metro plugin error" !in tooltip)
  }

  fun testMultiParentExtensionBadgeRequiresEveryContextToPass() {
    val file =
      myFixture.configureMetroFile(
        """
        @GraphExtension
        interface ChildGraph

        @DependencyGraph
        interface LeftParent {
          val child: ChildGraph
        }

        @DependencyGraph
        interface RightParent {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val child = index.graphs.single { it.name == "ChildGraph" }
    val contexts = index.contextsFor(child)
    assertEquals(2, contexts.size)
    val validationService = project.service<MetroGraphValidationService>()

    validationService.validate(file, contexts.first())
    DaemonCodeAnalyzer.getInstance(project).restart()
    highlightMetroFile()
    val partialTooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.GRAPH }
        .mapNotNull { it.tooltipText }
    assertTrue(partialTooltips.toString()) {
      partialTooltips.any { "no problems found in 1 of 2 contexts" in it }
    }
    assertTrue(myFixture.findAllGutters().none { it.icon === MetroIcons.GRAPH_VALIDATED })

    val pinService = project.service<GraphContextPinService>()
    val pinnedRoot = contexts.first().rootGraph
    pinService.pin(index.contextsFor(pinnedRoot).single().path)
    highlightMetroFile()
    assertEquals(
      1,
      myFixture.findAllGutters().count { it.icon === MetroIcons.GRAPH_VALIDATED },
    )
    pinService.clear()
    highlightMetroFile()
    assertTrue(myFixture.findAllGutters().none { it.icon === MetroIcons.GRAPH_VALIDATED })

    validationService.validate(file, contexts.last())
    DaemonCodeAnalyzer.getInstance(project).restart()
    highlightMetroFile()
    assertEquals(
      1,
      myFixture.findAllGutters().count { it.icon === MetroIcons.GRAPH_VALIDATED },
    )
  }

  fun testScopedProviderAndMultibindingConsumerTooltips() {
    myFixture.configureMetroFile(
      """
      interface Api
      interface Analytics

      interface ApiProviders {
        @Provides @SingleIn(AppScope::class) fun provideApi(): Api = object : Api {}
      }

      @Inject @ContributesIntoSet(AppScope::class) class DebugAnalytics : Analytics

      @DependencyGraph(AppScope::class, bindingContainers = [ApiProviders::class])
      interface AppGraph {
        val api: Api
        val analytics: Set<Analytics>
      }
      """
    )
    highlightMetroFile()
    val tooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.PROVIDER || it.icon === MetroIcons.CONSUMER }
        .mapNotNull { it.tooltipText }
    assertTrue(tooltips.toString()) {
      tooltips.any { it.startsWith("Metro provides: Api · scoped to AppScope") }
    }
    assertTrue(tooltips.toString()) {
      tooltips.any { it.startsWith("Metro dependency: Set<Analytics> · 1 contribution") }
    }
  }

  fun testProviderConsumerAndGraphMarkersArePresent() {
    val tooltips = configureAndHighlight()

    assertTrue("Expected a binds provider marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro binds: Service") }
    }
    assertTrue("Expected an injected class provider marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro injected class: Consumer") }
    }
    assertTrue("Expected a consumer marker for the service param in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro dependency: Service") }
    }
    assertTrue("Expected a graph accessor consumer marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro dependency: Consumer") }
    }
    assertTrue("Expected a graph contributions marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Contributions to AppScope") }
    }
    assertTrue("Expected an unresolved-consumer marker for the missing param in:\n$tooltips") {
      tooltips.any {
        it.startsWith("Metro dependency: Long") && "no binding found in project sources" in it
      }
    }
    assertTrue("Expected no assisted gutter markers (inlay-only):\n$tooltips") {
      tooltips.none { it.startsWith("Metro: assisted parameter") }
    }
  }

  fun testAssistedTargetDoesNotAppearAsAnInjectableBinding() {
    myFixture.configureMetroFile(
      """
      @AssistedInject class Widget(@Assisted val id: String)

      @AssistedFactory
      interface WidgetFactory {
        fun create(id: String): Widget
      }

      @Inject class Screen(val widget: Widget)

      @DependencyGraph
      interface AppGraph {
        val screen: Screen
      }
      """
    )
    highlightMetroFile()
    val gutters = myFixture.findAllGutters()
    val providerTooltips =
      gutters.filter { it.icon === MetroIcons.PROVIDER }.mapNotNull { it.tooltipText }
    val missingTooltips =
      gutters.filter { it.icon === MetroIcons.CONSUMER_UNRESOLVED }.mapNotNull { it.tooltipText }

    assertTrue(providerTooltips.toString()) {
      providerTooltips.none { it.startsWith("Metro injected class: Widget") }
    }
    assertTrue(missingTooltips.toString()) {
      missingTooltips.any { it.startsWith("Metro dependency: Widget") }
    }
  }

  fun testBindingMissingFromSomeContextsUsesAttentionMarker() {
    myFixture.configureMetroFile(
      """
      abstract class OtherScope

      interface Repo

      @Inject
      @ContributesBinding(AppScope::class)
      class AppRepo : Repo

      @Inject class Consumer(val repo: Repo)

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val consumer: Consumer
      }

      @DependencyGraph(OtherScope::class)
      interface OtherGraph {
        val consumer: Consumer
      }
      """
    )
    highlightMetroFile()

    val marker =
      myFixture.findAllGutters().single {
        it.icon === MetroIcons.CONSUMER_UNRESOLVED &&
          it.tooltipText?.startsWith("Metro dependency: Repo · binding found") == true
      }
    assertSame(MetroIcons.CONSUMER_UNRESOLVED, marker.icon)
    assertTrue(marker.tooltipText.orEmpty()) {
      "binding found in 1 of 2 graph contexts" in marker.tooltipText.orEmpty()
    }
  }

  fun testContextDependentBindingsDoNotUseAttentionMarker() {
    val file =
      myFixture.configureMetroFile(
        """
      abstract class OtherScope

      interface Repo

      @Inject
      @ContributesBinding(AppScope::class)
      class AppRepo : Repo

      @Inject
      @ContributesBinding(OtherScope::class)
      class OtherRepo : Repo

      @Inject class Consumer(val repo: Repo)

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val consumer: Consumer
      }

      @DependencyGraph(OtherScope::class)
      interface OtherGraph {
        val consumer: Consumer
      }
      """
      )
    highlightMetroFile()

    val marker =
      myFixture.findAllGutters().single {
        it.icon === MetroIcons.CONSUMER &&
          it.tooltipText?.startsWith("Metro dependency: Repo · bindings differ") == true
      }
    assertSame(MetroIcons.CONSUMER, marker.icon)
    assertTrue(marker.tooltipText.orEmpty()) {
      "bindings differ across 2 graph contexts · 2 candidates" in marker.tooltipText.orEmpty()
    }

    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val contexts =
      index.graphs.associate { graph ->
        graph.name to index.contextsFor(graph).single()
      }
    val pinService = project.service<GraphContextPinService>()

    pinService.pin(contexts.getValue("AppGraph").path)
    highlightMetroFile()
    val appMarker =
      myFixture.findAllGutters().single {
        it.icon === MetroIcons.CONSUMER &&
          it.tooltipText?.startsWith("Metro dependency: Repo") == true
      }
    assertSame(MetroIcons.CONSUMER, appMarker.icon)
    assertEquals(
      "Metro dependency: Repo · provided by AppRepo in AppGraph",
      appMarker.tooltipText,
    )

    pinService.pin(contexts.getValue("OtherGraph").path)
    highlightMetroFile()
    val otherMarker =
      myFixture.findAllGutters().single {
        it.icon === MetroIcons.CONSUMER &&
          it.tooltipText?.startsWith("Metro dependency: Repo") == true
      }
    assertSame(MetroIcons.CONSUMER, otherMarker.icon)
    assertEquals(
      "Metro dependency: Repo · provided by OtherRepo in OtherGraph",
      otherMarker.tooltipText,
    )
  }

  fun testNoMarkersWhenMetroDisabled() {
    project.setMetroOptions("enabled" to "false")
    val tooltips = configureAndHighlight()
    assertTrue("Expected no Metro markers in:\n$tooltips") {
      tooltips.none { it.startsWith("Metro ") }
    }
  }

  fun testNoMarkersWhenBindingResolutionSettingIsDisabled() {
    val settings = MetroSettings.getInstance(project).state
    settings.enableBindingResolution = false
    try {
      val tooltips = configureAndHighlight()
      assertTrue("Expected no Metro markers in:\n$tooltips") { tooltips.isEmpty() }
    } finally {
      settings.enableBindingResolution = true
    }
  }
}
