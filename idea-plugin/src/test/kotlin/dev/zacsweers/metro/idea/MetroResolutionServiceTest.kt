// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

class MetroResolutionServiceTest : BasePlatformTestCase() {

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
      import dev.zacsweers.metro.Binds
      import dev.zacsweers.metro.ContributesBinding
      import dev.zacsweers.metro.ContributesIntoSet
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.Inject
      import dev.zacsweers.metro.IntoMap
      import dev.zacsweers.metro.Named
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

      @ContributesBinding(AppScope::class)
      @SingleIn(AppScope::class)
      class RealHttpApi : HttpApi

      @ContributesIntoSet(AppScope::class) class DebugAnalytics : Analytics
      @ContributesIntoSet(AppScope::class) class ProdAnalytics : Analytics

      interface UrlProviders {
        @Provides @Named("cdn") fun provideCdnUrl(): String = "cdn"
        @Provides fun provideBaseUrl(): String = "base"
      }

      interface HandlerProviders {
        @Provides @IntoMap @StringKey("a") fun handlerA(): Service = ServiceImpl()
        @Provides @IntoMap @StringKey("b") fun handlerB(): Service = ServiceImpl()
      }

      @Inject
      class Consumer(
        val service: Service,
        val api: HttpApi,
        val analytics: Set<Analytics>,
        val handlers: Map<String, Service>,
        @Named("cdn") val cdnUrl: String,
      )

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val consumer: Consumer
      }
      """
        .trimIndent(),
    ) as KtFile
  }

  fun testBindsProviderIsIndexedWithImplementation() {
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.providerEntriesAt(declarations.function("bindService")).single()
    assertEquals(MetroProviderKind.BINDS, entry.kind)
    assertEquals("test.Service", entry.key.renderedType)
    assertEquals("ServiceImpl", entry.implementationName)

    // The @Binds impl parameter consumes the impl binding
    val implParam = declarations.parameter("impl")
    assertEquals("test.ServiceImpl", index.consumerEntryAt(implParam)?.key?.renderedType)
  }

  fun testInjectedClassProvidesItsOwnTypeAndConsumesConstructorParams() {
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.providerEntriesAt(declarations.klass("Consumer")).single()
    assertEquals(MetroProviderKind.INJECT, entry.kind)
    assertEquals("test.Consumer", entry.key.renderedType)

    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    assertEquals("test.Service", serviceParam.key.renderedType)
    assertTrue(serviceParam.isAbstractType)

    // The consumer's Service key resolves to the @Binds provider
    val providers = index.providersFor(serviceParam)
    assertEquals(listOf(MetroProviderKind.BINDS), providers.map { it.kind })
  }

  fun testContributedBindingBindsItsSoleSupertypeWithScope() {
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val entries = index.providerEntriesAt(declarations.klass("RealHttpApi"))
    val contributed = entries.single { it.kind == MetroProviderKind.CONTRIBUTED }
    assertEquals("test.HttpApi", contributed.key.renderedType)
    assertEquals("RealHttpApi", contributed.implementationName)
    assertEquals("@SingleIn(scope = AppScope::class)", contributed.scope?.render(short = true))

    val apiParam = index.consumerEntryAt(declarations.parameter("api"))!!
    assertEquals(listOf("RealHttpApi"), index.providersFor(apiParam).map { it.implementationName })
  }

  fun testSetMultibindingContributionsJoinTheirAggregateConsumer() {
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val analyticsParam = index.consumerEntryAt(declarations.parameter("analytics"))!!
    assertEquals("kotlin.collections.Set<test.Analytics>", analyticsParam.key.renderedType)
    assertEquals("test.Analytics", analyticsParam.multibindingId)

    // Contributions keep their element key, mirroring the compiler's @MultibindingElement model
    val contributors = index.providersFor(analyticsParam)
    assertEquals(2, contributors.size)
    assertTrue(contributors.all { it.kind == MetroProviderKind.MULTIBINDING_CONTRIBUTION })
    assertTrue(contributors.all { it.key.renderedType == "test.Analytics" })

    // And the reverse direction: a contribution's consumers include the aggregate site
    val debugAnalytics = index.providerEntriesAt(declarations.klass("DebugAnalytics"))
    val consumers = index.consumersFor(debugAnalytics)
    assertTrue(consumers.any { it.pointer.element === declarations.parameter("analytics") })
  }

  fun testMapMultibindingContributionsJoinTheirAggregateConsumer() {
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val handlersParam = index.consumerEntryAt(declarations.parameter("handlers"))!!
    assertEquals(
      "kotlin.collections.Map<kotlin.String, test.Service>",
      handlersParam.key.renderedType,
    )
    assertEquals("kotlin.String_test.Service", handlersParam.multibindingId)

    val contributors = index.providersFor(handlersParam)
    assertEquals(2, contributors.size)
    assertTrue(contributors.all { it.kind == MetroProviderKind.MULTIBINDING_CONTRIBUTION })
    assertEquals(
      setOf("handlerA", "handlerB"),
      contributors.mapNotNull { (it.pointer.element as? KtNamedDeclaration)?.name }.toSet(),
    )

    // The plain Service consumer is not polluted by map contributions
    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    assertEquals(listOf(MetroProviderKind.BINDS), index.providersFor(serviceParam).map { it.kind })
  }

  fun testQualifiersDisambiguateKeys() {
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val cdnParam = index.consumerEntryAt(declarations.parameter("cdnUrl"))!!
    assertEquals("@Named(name = \"cdn\") String", cdnParam.key.render(short = true))
    assertEquals(
      "@dev.zacsweers.metro.Named(name = \"cdn\") kotlin.String",
      cdnParam.key.render(short = false),
    )

    val providers = index.providersFor(cdnParam)
    assertEquals(1, providers.size)
    assertEquals("provideCdnUrl", (providers.single().pointer.element as? KtNamedDeclaration)?.name)
  }

  fun testConcreteInjectedClassConsumersAcrossInjectionShapes() {
    val file =
      myFixture.configureByText(
        "Shapes.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.Assisted
        import dev.zacsweers.metro.AssistedInject
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.SingleIn

        @Inject @SingleIn(AppScope::class) class Repository

        @Inject fun HomePresenter(repository: Repository): Int = 0

        @AssistedInject class DetailPresenter(@Assisted val id: String, val repo: Repository)

        @DependencyGraph(AppScope::class)
        interface ShapeGraph {
          val repository: Repository
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val repositoryEntries = index.providerEntriesAt(declarations.klass("Repository"))
    assertEquals(listOf(MetroProviderKind.INJECT), repositoryEntries.map { it.kind })

    val consumerElements =
      index.consumersFor(repositoryEntries).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertEquals(setOf("repository", "repo"), consumerElements.toSet())
    // 3 sites: the injected function param, the assisted class param, and the graph accessor
    assertEquals(3, consumerElements.size)

    // The @Assisted param is marked as supplied at creation time, not as a consumer
    val idParam = declarations.parameter("id")
    assertNull(index.consumerEntryAt(idParam))
    assertEquals("@Assisted", index.assistedSiteAt(idParam)?.supplier)
  }

  fun testGraphEntryExposesScopesAccessorsAndContributions() {
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    assertEquals(setOf(ClassId.fromString("dev/zacsweers/metro/AppScope")), graph.scopeKeys)

    // The accessor property is a consumer of Consumer
    val accessor = index.consumerEntryAt(declarations.property("consumer"))!!
    assertEquals("test.Consumer", accessor.key.renderedType)

    val contributions =
      index.contributionsForScopes(graph.scopeKeys).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertEquals(
      setOf("RealHttpApi", "DebugAnalytics", "ProdAnalytics"),
      contributions.toSet(),
    )
  }

  fun testCircuitParameterResolvesSingleContributedImplementation() {
    project.setMetroOptions("enable-circuit-codegen" to "true")
    myFixture.addCircuitStubs()
    val file =
      myFixture.configureByText(
        "CircuitImpl.kt",
        """
        package test

        import com.slack.circuit.codegen.annotations.CircuitInject
        import com.slack.circuit.runtime.CircuitUiState
        import com.slack.circuit.runtime.screen.Screen
        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.SingleIn

        class AreaScreen : Screen
        class AreaState : CircuitUiState

        interface Repo

        @SingleIn(AppScope::class)
        @ContributesBinding(AppScope::class)
        class RepoImpl(private val name: String) : Repo {
          @Inject constructor(count: Int) : this(count.toString())
        }

        @CircuitInject(AreaScreen::class, AppScope::class)
        fun AreaPresenter(screen: AreaScreen, repo: Repo): AreaState {
          return AreaState()
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    // The exact inputs the implementation inlay needs
    val consumer = index.consumerEntryAt(declarations.parameter("repo"))!!
    assertTrue(consumer.isAbstractType)
    val providers = index.providersFor(consumer)
    assertEquals(1, providers.size)
    assertEquals("RepoImpl", providers.single().implementationName)
  }

  fun testCircuitInjectDeclarationsContributeFactoriesAndConsumeParameters() {
    project.setMetroOptions("enable-circuit-codegen" to "true")
    myFixture.addCircuitStubs()
    val file =
      myFixture.configureByText(
        "Circuit.kt",
        """
        package test

        import androidx.compose.ui.Modifier
        import com.slack.circuit.codegen.annotations.CircuitInject
        import com.slack.circuit.runtime.CircuitUiState
        import com.slack.circuit.runtime.Navigator
        import com.slack.circuit.runtime.presenter.Presenter
        import com.slack.circuit.runtime.screen.Screen
        import com.slack.circuit.runtime.ui.Ui
        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        class HomeScreen : Screen
        class HomeState : CircuitUiState

        @Inject class Repository

        @CircuitInject(HomeScreen::class, AppScope::class)
        fun HomePresenter(repository: Repository, navigator: Navigator, screen: HomeScreen): HomeState {
          return HomeState()
        }

        @CircuitInject(HomeScreen::class, AppScope::class)
        fun HomeUi(state: HomeState, modifier: Modifier, repository: Repository) {
        }

        @DependencyGraph(AppScope::class)
        interface CircuitGraph {
          val uiFactories: Set<Ui.Factory>
          val presenterFactories: Set<Presenter.Factory>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    // Both functions contribute generated factories into the scope's factory sets
    val presenterEntry = index.providerEntriesAt(declarations.function("HomePresenter")).single()
    assertEquals(MetroProviderKind.MULTIBINDING_CONTRIBUTION, presenterEntry.kind)
    assertEquals(
      "com.slack.circuit.runtime.presenter.Presenter.Factory",
      presenterEntry.key.renderedType,
    )

    val uiEntry = index.providerEntriesAt(declarations.function("HomeUi")).single()
    assertEquals("com.slack.circuit.runtime.ui.Ui.Factory", uiEntry.key.renderedType)

    // The graph's factory set accessors resolve to the contributions
    val presenterFactories = index.consumerEntryAt(declarations.property("presenterFactories"))!!
    assertEquals(
      listOf("HomePresenter"),
      index.providersFor(presenterFactories).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    val uiFactories = index.consumerEntryAt(declarations.property("uiFactories"))!!
    assertEquals(
      listOf("HomeUi"),
      index.providersFor(uiFactories).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )

    // Injected params are consumers; circuit-provided params (navigator/screen/state/modifier)
    // are assisted sites instead
    val repositoryEntries = index.providerEntriesAt(declarations.klass("Repository"))
    val repositoryConsumers =
      index.consumersFor(repositoryEntries).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertEquals(listOf("repository", "repository"), repositoryConsumers.sorted())
    for (name in listOf("navigator", "screen", "state", "modifier")) {
      val parameter = declarations.parameter(name)
      assertNull(index.consumerEntryAt(parameter))
      assertEquals("Circuit", index.assistedSiteAt(parameter)?.supplier)
    }

    // And both declarations show up as contributions to the graph's scope
    val graph = index.graphEntryAt(declarations.klass("CircuitGraph"))!!
    val contributionNames =
      index.contributionsForScopes(graph.scopeKeys).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertTrue(contributionNames.containsAll(listOf("HomePresenter", "HomeUi")))
  }

  fun testGraphFactoryInstanceBindingsResolve() {
    val file =
      myFixture.configureByText(
        "Factory.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.Provides

        class Config

        @Inject class ConfigConsumer(val config: Config)

        @DependencyGraph(AppScope::class)
        interface FactoryGraph {
          val consumer: ConfigConsumer

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Provides providedConfig: Config): FactoryGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    // The factory's @Provides param is an instance binding, not a consumer
    val factoryParam = declarations.parameter("providedConfig")
    assertNull(index.consumerEntryAt(factoryParam))
    val instanceEntry = index.providerEntriesAt(factoryParam).single()
    assertEquals(MetroProviderKind.INSTANCE, instanceEntry.kind)
    assertEquals("test.Config", instanceEntry.key.renderedType)

    // And consumers of its type resolve to it
    val configParam = index.consumerEntryAt(declarations.parameter("config"))!!
    val providers = index.providersFor(configParam)
    assertEquals(listOf(MetroProviderKind.INSTANCE), providers.map { it.kind })
    assertTrue(providers.single().pointer.element === factoryParam)
  }

  fun testLibraryInjectClassesResolveOnDemand() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibConsumer.kt",
          """
          package test

          import dev.zacsweers.metro.Inject
          import libtest.LibHttpClient

          @Inject class LibConsumer(val client: LibHttpClient)
          """
            .trimIndent(),
        ) as KtFile
      val index = MetroResolutionService.getInstance(project).index(file)
      val declarations = file.declarationsIncludingNested()

      val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
      val providers = index.providersFor(clientParam)
      assertEquals(listOf(MetroProviderKind.INJECT), providers.map { it.kind })
      val target = providers.single().pointer.element
      assertEquals("LibHttpClient", (target as? KtNamedDeclaration)?.name)
      assertEquals(
        "@SingleIn(scope = AppScope::class)",
        providers.single().scope?.render(short = true),
      )
    }
  }

  fun testLibraryContributionsResolveViaHintFunctions() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibGraph.kt",
          """
          package test

          import dev.zacsweers.metro.AppScope
          import dev.zacsweers.metro.DependencyGraph
          import libtest.LibAnalytics
          import libtest.LibContained
          import libtest.LibExplicit
          import libtest.LibHidden
          import libtest.LibService

          @DependencyGraph(AppScope::class)
          interface LibGraph {
            val service: LibService
            val analytics: Set<LibAnalytics>
            val explicit: LibExplicit
            val contained: LibContained
            val hidden: LibHidden
          }
          """
            .trimIndent(),
        ) as KtFile
      val index = MetroResolutionService.getInstance(project).index(file)
      val declarations = file.declarationsIncludingNested()

      val serviceAccessor = index.consumerEntryAt(declarations.property("service"))!!
      val serviceProviders = index.providersFor(serviceAccessor)
      assertEquals(listOf(MetroProviderKind.CONTRIBUTED), serviceProviders.map { it.kind })
      assertEquals("LibServiceImpl", serviceProviders.single().implementationName)

      val analyticsAccessor = index.consumerEntryAt(declarations.property("analytics"))!!
      val analyticsProviders = index.providersFor(analyticsAccessor)
      assertEquals(
        listOf(MetroProviderKind.MULTIBINDING_CONTRIBUTION),
        analyticsProviders.map { it.kind },
      )
      assertEquals("LibAnalyticsImpl", analyticsProviders.single().implementationName)

      // Explicit binding<T>() bound types aren't recoverable from binary annotations; they
      // resolve through the generated nested MetroContribution @Binds members instead
      val explicitAccessor = index.consumerEntryAt(declarations.property("explicit"))!!
      val explicitProviders = index.providersFor(explicitAccessor)
      assertEquals(listOf(MetroProviderKind.BINDS), explicitProviders.map { it.kind })
      assertEquals("LibExplicitImpl", explicitProviders.single().implementationName)

      // Contribution-provider container objects expose their @Provides members, attributed to
      // the @Origin class
      val containedAccessor = index.consumerEntryAt(declarations.property("contained"))!!
      val containedProviders = index.providersFor(containedAccessor)
      assertEquals(listOf(MetroProviderKind.PROVIDES), containedProviders.map { it.kind })
      assertEquals("LibContainedImpl", containedProviders.single().implementationName)

      // Internal hints from non-friend modules are filtered, mirroring the compiler
      val hiddenAccessor = index.consumerEntryAt(declarations.property("hidden"))!!
      assertTrue(index.providersFor(hiddenAccessor).isEmpty())

      // Library contributions also appear in the graph's contribution list
      val graph = index.graphEntryAt(declarations.klass("LibGraph"))!!
      val contributionNames =
        index.contributionsForScopes(graph.scopeKeys).mapNotNull {
          (it.pointer.element as? KtNamedDeclaration)?.name
        }
      assertTrue(
        contributionNames.containsAll(
          listOf("LibServiceImpl", "LibAnalyticsImpl", "LibExplicitImpl", "LibContainedImpl")
        )
      )
      assertFalse("LibHiddenImpl" in contributionNames)
    }
  }

  fun testLibraryResolutionRespectsResolveFromLibrariesSetting() {
    val settings = MetroSettings.getInstance(project).state
    settings.resolveFromLibraries = false
    try {
      module.withMetroLibFixtureLibrary {
        val file =
          myFixture.configureByText(
            "LibConsumer.kt",
            """
            package test

            import dev.zacsweers.metro.Inject
            import libtest.LibHttpClient

            @Inject class LibConsumer(val client: LibHttpClient)
            """
              .trimIndent(),
          ) as KtFile
        val index = MetroResolutionService.getInstance(project).index(file)
        val declarations = file.declarationsIncludingNested()
        val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
        assertTrue(index.providersFor(clientParam).isEmpty())
      }
    } finally {
      settings.resolveFromLibraries = true
    }
  }

  fun testCustomProviderAndLazyWrappersAreUnwrapped() {
    project.setMetroOptions(
      "custom-provider" to "test/CustomProvider",
      "custom-lazy" to "test/CustomLazy",
    )
    val file =
      myFixture.configureByText(
        "CustomWrappers.kt",
        """
        package test

        import dev.zacsweers.metro.Binds
        import dev.zacsweers.metro.Inject

        class CustomProvider<T>
        class CustomLazy<T>

        interface Service
        @Inject class ServiceImpl : Service

        interface ServiceBindings {
          @Binds fun bindService(impl: ServiceImpl): Service
        }

        @Inject
        class Consumer(
          val serviceProvider: CustomProvider<Service>,
          val serviceLazy: CustomLazy<Service>,
        )
        """
          .trimIndent(),
      ) as KtFile
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    for (name in listOf("serviceProvider", "serviceLazy")) {
      val consumer = index.consumerEntryAt(declarations.parameter(name))!!
      assertEquals("test.Service", consumer.key.renderedType)
      assertEquals(listOf(MetroProviderKind.BINDS), index.providersFor(consumer).map { it.kind })
    }
  }

  fun testFunctionTypesAreNotUnwrappedWhenFunctionProvidersAreDisabled() {
    project.setMetroOptions("enable-function-providers" to "false")
    val file =
      myFixture.configureByText(
        "FunctionProvider.kt",
        """
        package test

        import dev.zacsweers.metro.Binds
        import dev.zacsweers.metro.Inject

        interface Service
        @Inject class ServiceImpl : Service

        interface ServiceBindings {
          @Binds fun bindService(impl: ServiceImpl): Service
        }

        @Inject class Consumer(val serviceFactory: () -> Service)
        """
          .trimIndent(),
      ) as KtFile
    val index = MetroResolutionService.getInstance(project).index(file)
    val declarations = file.declarationsIncludingNested()

    val consumer = index.consumerEntryAt(declarations.parameter("serviceFactory"))!!
    assertTrue(index.providersFor(consumer).isEmpty())
  }

  fun testInternalHintsFromProjectOwnedBinariesAreHonored() {
    module.withMetroLibFixtureLibrary(withinProject = true) {
      val file =
        myFixture.configureByText(
          "FriendGraph.kt",
          """
          package test

          import dev.zacsweers.metro.AppScope
          import dev.zacsweers.metro.DependencyGraph
          import libtest.LibHidden

          @DependencyGraph(AppScope::class)
          interface FriendGraph {
            val hidden: LibHidden
          }
          """
            .trimIndent(),
        ) as KtFile
      val index = MetroResolutionService.getInstance(project).index(file)
      val declarations = file.declarationsIncludingNested()

      // The hint is internal, but the binary belongs to this project (friend-module
      // approximation), so the contribution resolves
      val hiddenAccessor = index.consumerEntryAt(declarations.property("hidden"))!!
      val providers = index.providersFor(hiddenAccessor)
      assertEquals(listOf("LibHiddenImpl"), providers.map { it.implementationName })
    }
  }

  fun testIndexIsEmptyWhenMetroDisabled() {
    project.setMetroOptions("enabled" to "false")
    val file = configure()
    val index = MetroResolutionService.getInstance(project).index(file)
    assertTrue(index.providers.isEmpty())
    assertTrue(index.consumers.isEmpty())
    assertTrue(index.graphs.isEmpty())
  }
}
