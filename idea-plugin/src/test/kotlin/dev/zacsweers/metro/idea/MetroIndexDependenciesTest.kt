// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.aggregateMultibindingId
import org.jetbrains.kotlin.psi.KtFile

/**
 * Covers the seal-facing index enrichment: dependency keys on bindings, contextual keys on
 * consumers, map key values, and the pull-based per-graph queries.
 */
class MetroIndexDependenciesTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
  }

  private fun configure(): KtFile {
    return myFixture.configureByText(
      "Test.kt",
      """
      package test

      import dev.zacsweers.metro.AppScope
      import dev.zacsweers.metro.Assisted
      import dev.zacsweers.metro.AssistedFactory
      import dev.zacsweers.metro.AssistedInject
      import dev.zacsweers.metro.Binds
      import dev.zacsweers.metro.ContributesBinding
      import dev.zacsweers.metro.ContributesIntoSet
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.HasMemberInjections
      import dev.zacsweers.metro.Inject
      import dev.zacsweers.metro.IntoMap
      import dev.zacsweers.metro.Named
      import dev.zacsweers.metro.Provider
      import dev.zacsweers.metro.Provides
      import dev.zacsweers.metro.SingleIn
      import dev.zacsweers.metro.StringKey

      interface Service
      interface HttpApi
      interface Analytics

      @Inject class ServiceImpl : Service

      interface ServiceBindings {
        @Binds fun bindService(impl: ServiceImpl): Service
      }

      @Inject
      @ContributesBinding(AppScope::class)
      @SingleIn(AppScope::class)
      class RealHttpApi : HttpApi

      @ContributesIntoSet(AppScope::class) class DebugAnalytics : Analytics

      interface UrlProviders {
        @Provides fun provideBaseUrl(@Named("cdn") cdn: String, service: Service): String = cdn
        @Provides @Named("cdn") fun provideCdnUrl(): String = "cdn"
        @Provides fun Service.provideTag(): Int = 1
      }

      interface Tracker

      class Screen {
        @Inject lateinit var tracker: Tracker
      }

      @Inject
      class Telemetry(val telemetryService: Service) {
        @Inject lateinit var tracker: Tracker
      }

      @HasMemberInjections
      abstract class MarkedBase {
        @Inject lateinit var baseApi: HttpApi
      }

      abstract class UnmarkedBase {
        lateinit var ignoredApi: HttpApi
      }

      @Inject class MarkedChild(val childService: Service) : MarkedBase()

      @Inject class UnmarkedChild(val childService2: Service) : UnmarkedBase()

      interface HandlerProviders {
        @Provides @IntoMap @StringKey("a") fun handlerA(): Service = ServiceImpl()
        @Provides @IntoMap @StringKey("b") fun handlerB(): Service = ServiceImpl()
      }

      @AssistedInject class Widget(@Assisted val id: String, val api: HttpApi)

      @AssistedFactory
      interface WidgetFactory {
        fun create(id: String): Widget
      }

      @Inject
      class Consumer(
        val serviceProvider: Provider<Service>,
        val analytics: Set<Analytics>,
      )

      @DependencyGraph(
        AppScope::class,
        bindingContainers = [ServiceBindings::class, UrlProviders::class, HandlerProviders::class],
      )
      interface AppGraph {
        val consumer: Consumer
        val baseUrl: String

        fun inject(target: Screen)
      }
      """
        .trimIndent(),
    ) as KtFile
  }

  fun testProvidesFunctionParametersAreDependencies() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.function("provideBaseUrl")).single()
    assertEquals("provides", entry.label)
    assertEquals(
      listOf("@Named(name = \"cdn\") String", "Service"),
      entry.dependencies.map { it.typeKey.render(short = true) },
    )
  }

  fun testBindsDependencyIsItsSourceKey() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.function("bindService")).single()
    assertEquals("binds", entry.label)
    assertEquals(listOf("test.ServiceImpl"), entry.dependencies.map { it.typeKey.renderedType })
  }

  fun testInjectConstructorParametersAreDependencies() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.klass("Consumer")).single()
    assertEquals("injected class", entry.label)
    assertEquals(2, entry.dependencies.size)

    // Provider wrapper structure is preserved on the dependency's contextual key
    val providerDep = entry.dependencies[0]
    assertEquals("test.Service", providerDep.typeKey.renderedType)
    assertTrue(providerDep.wrappedType is WrappedType.Provider)
    assertTrue(providerDep.isDeferrable)

    // Aggregate dependency ids are deduced from the key itself
    val setDep = entry.dependencies[1]
    assertEquals("kotlin.collections.Set<test.Analytics>", setDep.typeKey.renderedType)
    assertEquals("test.Analytics", setDep.aggregateMultibindingId(MetroOptions()))
  }

  fun testContributedBindingAliasesItsOwnInjectBinding() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entries = index.bindingEntriesAt(declarations.klass("RealHttpApi"))
    val contributed = entries.single { it.label == "contributed binding" }
    assertEquals("test.HttpApi", contributed.key.renderedType)
    assertEquals(
      listOf("test.RealHttpApi"),
      contributed.dependencies.map { it.typeKey.renderedType },
    )
    // And the own-type inject binding carries no dependencies (no constructor params)
    val inject = entries.single { it.label == "injected class" }
    assertTrue(inject.dependencies.isEmpty())
  }

  fun testAssistedFactoryFlattensTargetConstructorDependencies() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.klass("WidgetFactory")).single()
    assertEquals("assisted factory", entry.label)
    // @Assisted id is supplied at create() time; only the graph-provided param remains
    assertEquals(listOf("test.HttpApi"), entry.dependencies.map { it.typeKey.renderedType })
  }

  fun testMapKeyValuesAreCaptured() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val handlerA = index.bindingEntriesAt(declarations.function("handlerA")).single()
    val handlerB = index.bindingEntriesAt(declarations.function("handlerB")).single()
    assertEquals(handlerA.multibindingId, handlerB.multibindingId)
    assertNotNull(handlerA.mapKeyValue)
    assertNotNull(handlerB.mapKeyValue)
    // Distinct key values, which duplicate detection groups by
    assertTrue(handlerA.mapKeyValue != handlerB.mapKeyValue)
    assertTrue(handlerA.mapKeyValue!!.contains("\"a\""))
  }

  fun testConsumerEntriesCarryContextualKeys() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val providerParam = index.consumerEntryAt(declarations.parameter("serviceProvider"))!!
    assertEquals("test.Service", providerParam.key.renderedType)
    assertTrue(providerParam.contextKey.wrappedType is WrappedType.Provider)

    val setParam = index.consumerEntryAt(declarations.parameter("analytics"))!!
    assertEquals("test.Analytics", setParam.multibindingId)
    assertEquals(
      setParam.multibindingId,
      setParam.contextKey.aggregateMultibindingId(MetroOptions()),
    )
  }

  fun testSealFacingQueries() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val context = index.contextFor(graph)

    // Direct key lookup respects graph membership
    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    val serviceBindings = index.bindingsForKey(serviceParam.key, context)
    assertEquals(listOf("binds"), serviceBindings.map { it.label })

    // Multibinding contributions resolve by aggregate id
    val contributions = index.multibindingContributions("test.Analytics", context)
    assertEquals(1, contributions.size)
    assertEquals("multibinding contribution", contributions.single().label)

    // Context-wide listing includes the contributed + provides bindings
    val allBindings = index.bindingsInContext(context)
    assertTrue(allBindings.any { it.label == "contributed binding" })
    assertTrue(allBindings.any { it.label == "provides" })

    // Roots: the graph's own accessors plus injector-driven member-inject keys
    val accessors = index.accessorsFor(graph)
    assertEquals(
      setOf("test.Consumer", "kotlin.String", "test.Tracker"),
      accessors.map { it.key.renderedType }.toSet(),
    )
  }

  fun testProvidesReceiverIsADependencyAndConsumer() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val provideTag = declarations.function("provideTag")
    val entry = index.bindingEntriesAt(provideTag).single()
    assertEquals("kotlin.Int", entry.key.renderedType)
    assertEquals(listOf("test.Service"), entry.dependencies.map { it.typeKey.renderedType })

    // The receiver is a consumer site anchored at its type reference
    val receiverConsumer = index.consumerEntryAt(provideTag.receiverTypeReference!!)!!
    assertEquals("test.Service", receiverConsumer.key.renderedType)
  }

  fun testMemberInjectSitesFoldIntoClassBindingDependencies() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.klass("Telemetry")).single()
    assertEquals("injected class", entry.label)
    assertEquals(
      listOf("test.Service", "test.Tracker"),
      entry.dependencies.map { it.typeKey.renderedType },
    )
  }

  fun testMemberInjectionsOnlyTraverseHasMemberInjectionsSuperclasses() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // Superclass marked @HasMemberInjections contributes its member-inject keys
    val marked = index.bindingEntriesAt(declarations.klass("MarkedChild")).single()
    assertEquals(
      listOf("test.Service", "test.HttpApi"),
      marked.dependencies.map { it.typeKey.renderedType },
    )

    // Unmarked superclasses are not traversed
    val unmarked = index.bindingEntriesAt(declarations.klass("UnmarkedChild")).single()
    assertEquals(listOf("test.Service"), unmarked.dependencies.map { it.typeKey.renderedType })
  }

  fun testGraphInjectorMembersConsumeTargetMemberInjectKeys() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val injectFunction = declarations.function("inject")
    val injectorConsumer = index.consumerEntryAt(injectFunction)!!
    assertEquals("test.Tracker", injectorConsumer.key.renderedType)

    // Injector-driven keys participate as seal roots alongside the accessors
    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val rootKeys = index.accessorsFor(graph).map { it.key.renderedType }.toSet()
    assertEquals(setOf("test.Consumer", "kotlin.String", "test.Tracker"), rootKeys)
  }

  fun testLibraryInjectBindingsCarryConstructorDependencies() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibConsumer.kt",
          """
          package test

          import dev.zacsweers.metro.Inject
          import libtest.LibClientWithDeps

          @Inject class LibConsumer(val client: LibClientWithDeps)
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().index(file)
      val declarations = file.declarationsIncludingNested()

      val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
      val binding = index.bindingsFor(clientParam).single()
      assertEquals("injected class", binding.label)
      assertEquals(
        listOf("libtest.LibHttpClient"),
        binding.dependencies.map { it.typeKey.renderedType },
      )
    }
  }
}
