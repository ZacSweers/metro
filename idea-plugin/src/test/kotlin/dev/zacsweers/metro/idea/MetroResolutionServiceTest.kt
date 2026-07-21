// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.ConsumerResolution
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty

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

  fun testBindsBindingIsIndexedWithImplementation() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.function("bindService")).single()
    assertEquals("binds", entry.label)
    assertEquals("test.Service", entry.typeKey.renderedType)
    assertEquals("ServiceImpl", entry.implementationName)

    // The @Binds impl parameter consumes the impl binding
    val implParam = declarations.parameter("impl")
    assertEquals("test.ServiceImpl", index.consumerEntryAt(implParam)?.key?.renderedType)
  }

  fun testInjectedClassProvidesItsOwnTypeAndConsumesConstructorParams() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.klass("Consumer")).single()
    assertEquals("injected class", entry.label)
    assertEquals("test.Consumer", entry.typeKey.renderedType)

    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    assertEquals("test.Service", serviceParam.key.renderedType)
    assertTrue(serviceParam.isAbstractType)

    // The consumer's Service key resolves to the @Binds provider
    val bindings = index.bindingsFor(serviceParam)
    assertEquals(listOf("binds"), bindings.map { it.label })
  }

  fun testContributedBindingBindsItsSoleSupertypeWithScope() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val entries = index.bindingEntriesAt(declarations.klass("RealHttpApi"))
    val contributed = entries.single { it.label == "contributed binding" }
    assertEquals("test.HttpApi", contributed.typeKey.renderedType)
    assertEquals("RealHttpApi", contributed.implementationName)
    assertEquals("@SingleIn(scope = AppScope::class)", contributed.scope?.render(short = true))

    val apiParam = index.consumerEntryAt(declarations.parameter("api"))!!
    assertEquals(listOf("RealHttpApi"), index.bindingsFor(apiParam).map { it.implementationName })
  }

  fun testSetMultibindingContributionsJoinTheirMultibindingConsumer() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val analyticsParam = index.consumerEntryAt(declarations.parameter("analytics"))!!
    assertEquals("kotlin.collections.Set<test.Analytics>", analyticsParam.key.renderedType)
    assertEquals("test.Analytics", analyticsParam.multibindingId)

    // Contributions keep their element key, mirroring the compiler's @MultibindingElement model
    val contributors = index.bindingsFor(analyticsParam)
    assertEquals(2, contributors.size)
    assertTrue(contributors.all { it.label == "multibinding contribution" })
    assertTrue(contributors.all { it.typeKey.renderedType == "test.Analytics" })

    // And the reverse direction: a contribution's consumers include the multibinding site
    val debugAnalytics = index.bindingEntriesAt(declarations.klass("DebugAnalytics"))
    val consumers = index.consumersFor(debugAnalytics)
    assertTrue(consumers.any { it.pointer.element === declarations.parameter("analytics") })
  }

  fun testMapMultibindingContributionsJoinTheirMultibindingConsumer() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val handlersParam = index.consumerEntryAt(declarations.parameter("handlers"))!!
    assertEquals(
      "kotlin.collections.Map<kotlin.String, test.Service>",
      handlersParam.key.renderedType,
    )
    assertEquals("kotlin.String_test.Service", handlersParam.multibindingId)

    val contributors = index.bindingsFor(handlersParam)
    assertEquals(2, contributors.size)
    assertTrue(contributors.all { it.label == "multibinding contribution" })
    assertEquals(
      setOf("handlerA", "handlerB"),
      contributors.mapNotNull { (it.pointer.element as? KtNamedDeclaration)?.name }.toSet(),
    )

    // The plain Service consumer is not polluted by map contributions
    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    assertEquals(listOf("binds"), index.bindingsFor(serviceParam).map { it.label })
  }

  fun testQualifiersDisambiguateKeys() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val cdnParam = index.consumerEntryAt(declarations.parameter("cdnUrl"))!!
    assertEquals("@Named(name = \"cdn\") String", cdnParam.key.render(short = true))
    assertEquals(
      "@dev.zacsweers.metro.Named(name = \"cdn\") kotlin.String",
      cdnParam.key.render(short = false),
    )

    val bindings = index.bindingsFor(cdnParam)
    assertEquals(1, bindings.size)
    assertEquals("provideCdnUrl", (bindings.single().pointer.element as? KtNamedDeclaration)?.name)
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
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val repositoryEntries = index.bindingEntriesAt(declarations.klass("Repository"))
    assertEquals(listOf("injected class"), repositoryEntries.map { it.label })

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
    val index = project.service<MetroResolutionService>().index(file)
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
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // The exact inputs the implementation inlay needs
    val consumer = index.consumerEntryAt(declarations.parameter("repo"))!!
    assertTrue(consumer.isAbstractType)
    val bindings = index.bindingsFor(consumer)
    assertEquals(1, bindings.size)
    assertEquals("RepoImpl", bindings.single().implementationName)
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

        abstract class OtherScope

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

        @DependencyGraph(OtherScope::class)
        interface OtherCircuitGraph {
          val otherUiFactories: Set<Ui.Factory>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // Both functions contribute generated factories into the scope's factory sets
    val presenterEntry = index.bindingEntriesAt(declarations.function("HomePresenter")).single()
    assertEquals("multibinding contribution", presenterEntry.label)
    assertEquals(
      "com.slack.circuit.runtime.presenter.Presenter.Factory",
      presenterEntry.typeKey.renderedType,
    )

    val uiEntry = index.bindingEntriesAt(declarations.function("HomeUi")).single()
    assertEquals("com.slack.circuit.runtime.ui.Ui.Factory", uiEntry.typeKey.renderedType)
    assertEquals(
      setOf(ClassId.topLevel(FqName("dev.zacsweers.metro.AppScope"))),
      uiEntry.contributionScopes,
    )

    // The graph's factory set accessors resolve to the contributions
    val presenterFactories = index.consumerEntryAt(declarations.property("presenterFactories"))!!
    assertEquals(
      listOf("HomePresenter"),
      index.bindingsFor(presenterFactories).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    val uiFactories = index.consumerEntryAt(declarations.property("uiFactories"))!!
    assertEquals(
      listOf("HomeUi"),
      index.bindingsFor(uiFactories).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )

    val otherGraph =
      index.contextsFor(index.graphEntryAt(declarations.klass("OtherCircuitGraph"))!!).single()
    val otherUiFactories = index.consumerEntryAt(declarations.property("otherUiFactories"))!!
    assertTrue(index.bindingsFor(otherUiFactories, index.queryContext(otherGraph)!!).isEmpty())

    // Injected params are consumers; circuit-provided params (navigator/screen/state/modifier)
    // are assisted sites instead
    val repositoryEntries = index.bindingEntriesAt(declarations.klass("Repository"))
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
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // The factory's @Provides param is an instance binding, not a consumer
    val factoryParam = declarations.parameter("providedConfig")
    assertNull(index.consumerEntryAt(factoryParam))
    val instanceEntry = index.bindingEntriesAt(factoryParam).single()
    assertEquals("instance binding", instanceEntry.label)
    assertEquals("test.Config", instanceEntry.typeKey.renderedType)

    // And consumers of its type resolve to it
    val configParam = index.consumerEntryAt(declarations.parameter("config"))!!
    val bindings = index.bindingsFor(configParam)
    assertEquals(listOf("instance binding"), bindings.map { it.label })
    assertTrue(bindings.single().pointer.element === factoryParam)
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
      val index = project.service<MetroResolutionService>().index(file)
      val declarations = file.declarationsIncludingNested()

      val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
      val bindings = index.bindingsFor(clientParam)
      assertEquals(listOf("injected class"), bindings.map { it.label })
      val target = bindings.single().pointer.element
      assertEquals("LibHttpClient", (target as? KtNamedDeclaration)?.name)
      assertEquals(
        "@SingleIn(scope = AppScope::class)",
        bindings.single().scope?.render(short = true),
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
      val index = project.service<MetroResolutionService>().index(file)
      val declarations = file.declarationsIncludingNested()

      val serviceAccessor = index.consumerEntryAt(declarations.property("service"))!!
      val serviceBindings = index.bindingsFor(serviceAccessor)
      assertEquals(listOf("contributed binding"), serviceBindings.map { it.label })
      assertEquals("LibServiceImpl", serviceBindings.single().implementationName)

      val analyticsAccessor = index.consumerEntryAt(declarations.property("analytics"))!!
      val analyticsBindings = index.bindingsFor(analyticsAccessor)
      assertEquals(
        listOf("multibinding contribution"),
        analyticsBindings.map { it.label },
      )
      assertEquals("LibAnalyticsImpl", analyticsBindings.single().implementationName)

      // Explicit binding<T>() bound types aren't recoverable from binary annotations; they
      // resolve through the generated nested MetroContribution @Binds members instead
      val explicitAccessor = index.consumerEntryAt(declarations.property("explicit"))!!
      val explicitBindings = index.bindingsFor(explicitAccessor)
      assertEquals(listOf("binds"), explicitBindings.map { it.label })
      assertEquals("LibExplicitImpl", explicitBindings.single().implementationName)

      // Contribution-provider container objects expose their @Provides members, attributed to
      // the @Origin class
      val containedAccessor = index.consumerEntryAt(declarations.property("contained"))!!
      val containedBindings = index.bindingsFor(containedAccessor)
      assertEquals(listOf("provides"), containedBindings.map { it.label })
      assertEquals("LibContainedImpl", containedBindings.single().implementationName)

      // Internal hints from non-friend modules are filtered, mirroring the compiler
      val hiddenAccessor = index.consumerEntryAt(declarations.property("hidden"))!!
      assertTrue(index.bindingsFor(hiddenAccessor).isEmpty())

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
        val index = project.service<MetroResolutionService>().index(file)
        val declarations = file.declarationsIncludingNested()
        val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
        assertTrue(index.bindingsFor(clientParam).isEmpty())
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
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    for (name in listOf("serviceProvider", "serviceLazy")) {
      val consumer = index.consumerEntryAt(declarations.parameter(name))!!
      assertEquals("test.Service", consumer.key.renderedType)
      assertEquals(listOf("binds"), index.bindingsFor(consumer).map { it.label })
    }
  }

  fun testBindsOptionalOfExposesOptionalBinding() {
    project.setMetroOptions("enable-dagger-runtime-interop" to "true")
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      """
      package dagger

      annotation class BindsOptionalOf
      """
        .trimIndent(),
    )
    // The light test fixture's mock JDK lacks java.util.Optional; stub it so it resolves.
    myFixture.addFileToProject(
      "java/util/Optional.kt",
      """
      package java.util

      class Optional<T>
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "Optionals.kt",
        """
        package test

        import dagger.BindsOptionalOf
        import dev.zacsweers.metro.BindingContainer
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import java.util.Optional

        interface Service

        @BindingContainer
        interface ServiceBindings {
          @BindsOptionalOf fun optionalService(): Service
        }

        @DependencyGraph(bindingContainers = [ServiceBindings::class])
        interface AppGraph {
          val service: Optional<Service>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // The @BindsOptionalOf declaration exposes an Optional<Service> binding.
    val optionalBinding = index.bindingEntriesAt(declarations.function("optionalService")).single()
    assertEquals("optional binding", optionalBinding.label)
    assertEquals("java.util.Optional<test.Service>", optionalBinding.typeKey.renderedType)
    val wrappedDependency = optionalBinding.dependencies.single()
    assertEquals("test.Service", wrappedDependency.typeKey.renderedType)
    assertTrue(wrappedDependency.hasDefault)

    val consumer = index.consumerEntryAt(declarations.property("service"))!!
    assertEquals("java.util.Optional<test.Service>", consumer.key.renderedType)
    assertEquals(listOf("optional binding"), index.bindingsFor(consumer).map { it.label })
    val context = index.contextsFor(index.graphEntryAt(declarations.klass("AppGraph"))!!).single()
    val queryContext = index.queryContext(context)!!
    assertEquals(
      listOf("optional binding"),
      index.bindingsFor(consumer, queryContext).map { it.label },
    )
  }

  fun testBindsOptionalOfIgnoredWithoutDaggerInterop() {
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      """
      package dagger

      annotation class BindsOptionalOf
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "OptionalsOff.kt",
        """
        package test

        import dagger.BindsOptionalOf
        import java.util.Optional

        interface Service

        interface ServiceBindings {
          @BindsOptionalOf fun optionalService(): Service
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()
    assertTrue(index.bindingEntriesAt(declarations.function("optionalService")).isEmpty())
  }

  fun testOptionalBindingMarksConsumersOptional() {
    val file =
      myFixture.configureByText(
        "OptionalMarker.kt",
        """
        package test

        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.OptionalBinding

        interface HttpClient

        @DependencyGraph
        interface AppGraph {
          @OptionalBinding val httpClient: HttpClient? get() = null
        }

        @Inject
        class Consumer(
          val flag: Boolean = false,
          val required: HttpClient,
        )
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // The @OptionalBinding accessor is a consumer (despite its default body) and is optional.
    val accessor = index.consumerEntryAt(declarations.property("httpClient"))!!
    assertTrue(accessor.isOptional)

    // Under DEFAULT behavior, a defaulted parameter is optional; a required one is not.
    assertTrue(index.consumerEntryAt(declarations.parameter("flag"))!!.isOptional)
    assertFalse(index.consumerEntryAt(declarations.parameter("required"))!!.isOptional)
  }

  fun testRequireOptionalBindingIgnoresBareDefaults() {
    project.setMetroOptions("optional-binding-behavior" to "REQUIRE_OPTIONAL_BINDING")
    val file =
      myFixture.configureByText(
        "RequireOptional.kt",
        """
        package test

        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.OptionalBinding

        interface HttpClient

        @Inject
        class Consumer(
          val bare: Boolean = false,
          @OptionalBinding val marked: HttpClient,
        )
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // A bare default no longer counts; only the explicit annotation does.
    assertFalse(index.consumerEntryAt(declarations.parameter("bare"))!!.isOptional)
    assertTrue(index.consumerEntryAt(declarations.parameter("marked"))!!.isOptional)
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
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val consumer = index.consumerEntryAt(declarations.parameter("serviceFactory"))!!
    assertTrue(index.bindingsFor(consumer).isEmpty())
  }

  fun testInternalHintsFromProjectOwnedBinariesAreFilteredWithoutFriendship() {
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
      val index = project.service<MetroResolutionService>().index(file)
      val declarations = file.declarationsIncludingNested()

      // Project-path ownership is not a visibility relationship; internal hints still require a
      // formal friend/associated compilation relationship.
      val hiddenAccessor = index.consumerEntryAt(declarations.property("hidden"))!!
      assertTrue(index.bindingsFor(hiddenAccessor).isEmpty())
    }
  }

  fun testAssistedFactoriesProvideTheirOwnType() {
    val file =
      myFixture.configureByText(
        "Assisted.kt",
        """
        package test

        import dev.zacsweers.metro.Assisted
        import dev.zacsweers.metro.AssistedFactory
        import dev.zacsweers.metro.AssistedInject
        import dev.zacsweers.metro.Inject

        class Engine @AssistedInject constructor(@Assisted val id: String)

        @AssistedFactory
        interface EngineFactory {
          fun create(id: String): Engine
        }

        @Inject class EngineUser(val factory: EngineFactory)
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val factoryEntry = index.bindingEntriesAt(declarations.klass("EngineFactory")).single()
    assertEquals("assisted factory", factoryEntry.label)
    assertEquals("test.EngineFactory", factoryEntry.typeKey.renderedType)
    assertEquals("Engine", factoryEntry.implementationName)

    val factoryParam = index.consumerEntryAt(declarations.parameter("factory"))!!
    assertEquals(
      listOf("assisted factory"),
      index.bindingsFor(factoryParam).map { it.label },
    )
  }

  fun testDefaultBindingSuppliesImplicitBoundType() {
    val file =
      myFixture.configureByText(
        "Defaults.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesIntoSet
        import dev.zacsweers.metro.DefaultBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        @DefaultBinding<BaseFactory<*>>
        interface BaseFactory<T : BaseFactory<T>>

        interface OtherMarker

        @ContributesIntoSet(AppScope::class)
        @Inject
        class HomeFactory : BaseFactory<HomeFactory>, OtherMarker

        @DependencyGraph(AppScope::class)
        interface DefaultsGraph {
          val factories: Set<BaseFactory<*>>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // Two supertypes, no explicit binding<T>() — the @DefaultBinding supertype decides
    val accessor = index.consumerEntryAt(declarations.property("factories"))!!
    val contributors = index.bindingsFor(accessor)
    assertEquals(listOf("HomeFactory"), contributors.map { it.implementationName })
    assertEquals("test.BaseFactory<*>", contributors.single().typeKey.renderedType)
  }

  fun testAmbiguousDefaultBindingsLeaveContributionUnresolved() {
    val file =
      myFixture.configureByText(
        "AmbiguousDefaults.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DefaultBinding
        import dev.zacsweers.metro.Inject

        @DefaultBinding<MarkerA>
        interface MarkerA

        @DefaultBinding<MarkerB>
        interface MarkerB

        @ContributesBinding(AppScope::class)
        @Inject
        class Impl : MarkerA, MarkerB
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    // Two supertypes both declare @DefaultBinding, so the bound type is ambiguous — no contributed
    // binding is originated (matching the compiler, rather than arbitrarily picking the first).
    val entries = index.bindingEntriesAt(declarations.klass("Impl"))
    assertTrue(entries.any { it.label == "injected class" })
    assertTrue(entries.none { it.label == "contributed binding" })
  }

  fun testClassKeyMapContributionsResolve() {
    val file =
      myFixture.configureByText(
        "ClassKeys.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ClassKey
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.IntoMap
        import dev.zacsweers.metro.Provides
        import kotlin.reflect.KClass

        interface Handler
        class FooHandler : Handler
        class Foo

        interface HandlerProviders {
          @Provides @IntoMap @ClassKey(Foo::class) fun fooHandler(): Handler = FooHandler()
        }

        @Inject class HandlerUser(val handlers: Map<KClass<*>, Handler>)
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val handlersParam = index.consumerEntryAt(declarations.parameter("handlers"))!!
    val contributors = index.bindingsFor(handlersParam)
    assertEquals(listOf("multibinding contribution"), contributors.map { it.label })
  }

  fun testReplacedContributionsLosePerGraph() {
    val file =
      myFixture.configureByText(
        "Replaces.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        interface Repo

        @ContributesBinding(AppScope::class)
        @Inject
        class RealRepo : Repo

        @ContributesBinding(AppScope::class, replaces = [RealRepo::class])
        @Inject
        class FakeRepo : Repo

        @DependencyGraph(AppScope::class)
        interface ReplacesGraph {
          val repo: Repo
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val accessor = index.consumerEntryAt(declarations.property("repo"))!!
    val resolution = index.resolveConsumer(accessor)
    assertEquals(2, resolution.global.size)
    // In the graph, the replacement wins
    assertEquals(
      listOf("FakeRepo"),
      resolution.uniformBindings.orEmpty().map { it.implementationName },
    )

    val realEntry =
      index.bindingEntriesAt(declarations.klass("RealRepo")).single {
        it.label == "contributed binding"
      }
    val fakeEntry =
      index.bindingEntriesAt(declarations.klass("FakeRepo")).single {
        it.label == "contributed binding"
      }
    assertTrue(index.consumersFor(listOf(realEntry)).isEmpty())
    assertEquals(
      listOf("repo"),
      index.consumersFor(listOf(fakeEntry)).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testExcludedContributionsAreDroppedFromGraphContext() {
    val file =
      myFixture.configureByText(
        "Excludes.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        interface Thing

        @ContributesBinding(AppScope::class)
        @Inject
        class NoisyThing : Thing

        @DependencyGraph(AppScope::class, excludes = [NoisyThing::class])
        interface ExcludesGraph {
          val thing: Thing
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("ExcludesGraph"))!!
    val context = index.contextsFor(graph).single()
    val queryContext = index.queryContext(context)!!
    assertTrue(context.excludes.isNotEmpty())

    val accessor = index.consumerEntryAt(declarations.property("thing"))!!
    assertTrue(index.bindingsFor(accessor, queryContext).isEmpty())
    assertTrue(index.contributionsFor(queryContext).isEmpty())
    // Global resolution still sees it as a candidate
    assertEquals(1, index.resolveConsumer(accessor).global.size)
  }

  fun testBindingContainersGateBindingsPerGraph() {
    val file =
      myFixture.configureByText(
        "Containers.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.BindingContainer
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Provides

        class Client
        class Api

        @BindingContainer
        object NetBindings {
          @Provides fun client(): Client = Client()
        }

        @BindingContainer(includes = [NetBindings::class])
        object AppBindings {
          @Provides fun api(client: Client): Api = Api()
        }

        @DependencyGraph(AppScope::class, bindingContainers = [AppBindings::class])
        interface WiredGraph {
          val api: Api
          val client: Client
        }

        @DependencyGraph
        interface UnwiredGraph {
          val unwiredClient: Client
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val wired = index.contextsFor(index.graphEntryAt(declarations.klass("WiredGraph"))!!).single()
    // Transitive container includes are expanded
    assertEquals(2, index.queryContext(wired)!!.containers.size)
    val clientAccessor = index.consumerEntryAt(declarations.property("client"))!!
    assertEquals(1, index.bindingsFor(clientAccessor, index.queryContext(wired)!!).size)

    val unwired =
      index.contextsFor(index.graphEntryAt(declarations.klass("UnwiredGraph"))!!).single()
    val unwiredAccessor = index.consumerEntryAt(declarations.property("unwiredClient"))!!
    assertTrue(index.bindingsFor(unwiredAccessor, index.queryContext(unwired)!!).isEmpty())
  }

  fun testIncludedDependencyAccessorsProvide() {
    val file =
      myFixture.configureByText(
        "Includes.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes

        class Client

        interface FactoryBase<G, D> {
          fun create(@Includes deps: D): G
        }

        interface NetworkDeps<T> {
          val client: T
        }

        @DependencyGraph(AppScope::class)
        interface IncludesGraph {
          val deps: NetworkDeps<Client>
          val graphClient: Client

          @DependencyGraph.Factory
          interface Factory : FactoryBase<IncludesGraph, NetworkDeps<Client>>
        }

        @DependencyGraph(AppScope::class)
        interface OtherGraph {
          val otherClient: Client

          @DependencyGraph.Factory
          interface Factory : FactoryBase<OtherGraph, NetworkDeps<Client>>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("IncludesGraph"))!!
    val context = index.contextsFor(graph).single()
    val queryContext = index.queryContext(context)!!
    assertEquals(
      setOf("test.NetworkDeps<test.Client>"),
      context.includedDependencies.mapTo(mutableSetOf()) { it.renderedType },
    )

    val accessor = index.consumerEntryAt(declarations.property("graphClient"))!!
    val bindings = index.bindingsFor(accessor, queryContext)
    assertEquals(listOf("included dependency accessor"), bindings.map { it.label })
    assertEquals("test.Client", bindings.single().typeKey.renderedType)
    assertEquals(
      listOf("test.NetworkDeps<test.Client>"),
      bindings.single().dependencies.map { it.typeKey.renderedType },
    )
    // Anchored at the dependency's accessor declaration
    assertEquals(
      "client",
      (bindings.single().pointer.element as? KtNamedDeclaration)?.name,
    )

    val ownerAccessor = index.consumerEntryAt(declarations.property("deps"))!!
    assertEquals(
      listOf("instance binding"),
      index.bindingsFor(ownerAccessor, queryContext).map { it.label },
    )

    val otherGraph = index.graphEntryAt(declarations.klass("OtherGraph"))!!
    val otherContext = index.queryContext(index.contextsFor(otherGraph).single())!!
    val otherAccessor = index.consumerEntryAt(declarations.property("otherClient"))!!
    assertEquals(
      listOf("included dependency accessor"),
      index.bindingsFor(otherAccessor, otherContext).map { it.label },
    )

    // The concrete factory input is shared rather than recreated once per including graph.
    assertEquals(
      1,
      index.bindings.filterIsInstance<KaBinding.GraphDependency>().count {
        it.ownerKey.renderedType == "test.NetworkDeps<test.Client>"
      },
    )
    assertEquals(
      1,
      index.bindings.filterIsInstance<KaBinding.BoundInstance>().count {
        it.isGraphInput && it.typeKey.renderedType == "test.NetworkDeps<test.Client>"
      },
    )
  }

  fun testIncludedGenericBindingContainersUseConcreteTypes() {
    val file =
      myFixture.configureByText(
        "GenericIncludes.kt",
        """
        package test

        import dev.zacsweers.metro.BindingContainer
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes
        import dev.zacsweers.metro.Provides

        class Box<T>(val value: T)

        @BindingContainer
        interface GenericBindings<T> {
          @Provides fun value(): T = error("not called")
          @Provides fun box(value: T): Box<T> = Box(value)

          companion object {
            @Provides fun count(): Long = 1L
          }
        }

        @DependencyGraph
        interface StringGraph {
          val stringValue: String
          val stringBox: Box<String>
          val count: Long

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes bindings: GenericBindings<String>): StringGraph
          }
        }

        @DependencyGraph
        interface IntGraph {
          val intValue: Int

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes bindings: GenericBindings<Int>): IntGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val stringGraph = index.graphEntryAt(declarations.klass("StringGraph"))!!
    val stringContext = index.contextsFor(stringGraph).single()
    val stringQueryContext = index.queryContext(stringContext)!!
    assertEquals(
      setOf("test.GenericBindings<kotlin.String>"),
      stringContext.includedBindingContainers.mapTo(mutableSetOf()) { it.renderedType },
    )

    val valueAccessor = index.consumerEntryAt(declarations.property("stringValue"))!!
    assertEquals(
      listOf("kotlin.String"),
      index.bindingsFor(valueAccessor, stringQueryContext).map { it.typeKey.renderedType },
    )
    val boxAccessor = index.consumerEntryAt(declarations.property("stringBox"))!!
    val boxBinding = index.bindingsFor(boxAccessor, stringQueryContext).single()
    assertEquals("test.Box<kotlin.String>", boxBinding.typeKey.renderedType)
    assertEquals(
      listOf("test.GenericBindings<kotlin.String>", "kotlin.String"),
      boxBinding.dependencies.map { it.typeKey.renderedType },
    )
    val countAccessor = index.consumerEntryAt(declarations.property("count"))!!
    assertEquals(1, index.bindingsFor(countAccessor, stringQueryContext).size)

    val intGraph = index.graphEntryAt(declarations.klass("IntGraph"))!!
    val intQueryContext = index.queryContext(index.contextsFor(intGraph).single())!!
    val intAccessor = index.consumerEntryAt(declarations.property("intValue"))!!
    assertEquals(
      listOf("kotlin.Int"),
      index.bindingsFor(intAccessor, intQueryContext).map { it.typeKey.renderedType },
    )
    assertTrue(index.bindingsFor(valueAccessor, intQueryContext).isEmpty())
  }

  fun testIncludedDependencyShardTracksAccessorFileChanges() {
    val dependencyFile =
      myFixture.addFileToProject(
        "test/NetworkDeps.kt",
        """
        package test

        class First
        class Second

        interface NetworkDeps {
          val first: First
        }
        """
          .trimIndent(),
      ) as KtFile
    val graphFile =
      myFixture.configureByText(
        "Graph.kt",
        """
        package test

        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes

        @DependencyGraph
        interface AppGraph {
          val second: Second

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes deps: NetworkDeps): AppGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val declarations = graphFile.declarationsIncludingNested()
    val accessor = declarations.property("second")

    val initialIndex = project.service<MetroResolutionService>().index(graphFile)
    val initialGraph = initialIndex.graphEntryAt(declarations.klass("AppGraph"))!!
    val initialContext =
      initialIndex.queryContext(initialIndex.contextsFor(initialGraph).single())!!
    val initialConsumer = initialIndex.consumerEntryAt(accessor)!!
    assertTrue(initialIndex.bindingsFor(initialConsumer, initialContext).isEmpty())

    myFixture.openFileInEditor(dependencyFile.virtualFile)
    myFixture.editor.caretModel.moveToOffset(dependencyFile.text.lastIndexOf('}'))
    myFixture.type("  val second: Second\n")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updatedIndex = project.service<MetroResolutionService>().index(graphFile)
    val updatedGraph = updatedIndex.graphEntryAt(declarations.klass("AppGraph"))!!
    val updatedContext =
      updatedIndex.queryContext(updatedIndex.contextsFor(updatedGraph).single())!!
    val updatedConsumer = updatedIndex.consumerEntryAt(accessor)!!
    assertEquals(
      listOf("included dependency accessor"),
      updatedIndex.bindingsFor(updatedConsumer, updatedContext).map { it.label },
    )
  }

  fun testGraphExtensionsInheritParentContext() {
    val file =
      myFixture.configureByText(
        "Extensions.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.GraphExtension
        import dev.zacsweers.metro.Inject

        abstract class ChildScope
        abstract class OtherScope

        interface Thing

        @ContributesBinding(AppScope::class)
        @Inject
        class RealThing : Thing

        @ContributesBinding(OtherScope::class)
        @Inject
        class OtherThing : Thing

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
          val thing: Thing

          @GraphExtension.Factory
          interface Factory {
            fun create(): ChildGraph
          }
        }

        @DependencyGraph(AppScope::class)
        interface ParentGraph {
          val childGraph: ChildGraph
          val childFactory: ChildGraph.Factory
        }

        @DependencyGraph(OtherScope::class)
        interface OtherParentGraph {
          val childGraph: ChildGraph
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val child = index.graphEntryAt(declarations.klass("ChildGraph"))!!
    assertTrue(child.isExtension)
    val childContexts = index.contextsFor(child)
    assertEquals(2, childContexts.size)

    // The child's accessor resolves through every parent scope that creates it
    val accessor = index.consumerEntryAt(declarations.property("thing"))!!
    val bindings = childContexts.flatMap { childContext ->
      index.bindingsFor(accessor, index.queryContext(childContext)!!)
    }
    assertEquals(setOf("RealThing", "OtherThing"), bindings.map { it.implementationName }.toSet())
    val resolutionByParent =
      index.resolveConsumer(accessor).perContext.mapKeys { (context, _) -> context.chain[1].name }
    assertEquals(
      mapOf(
        "ParentGraph" to listOf("RealThing"),
        "OtherParentGraph" to listOf("OtherThing"),
      ),
      resolutionByParent.mapValues { (_, parentBindings) ->
        parentBindings.map { it.implementationName }
      },
    )
    val resolution = index.resolveConsumer(accessor)
    assertNull(resolution.uniformBindings)
    assertEquals(
      setOf("RealThing", "OtherThing"),
      resolution.candidateBindings.mapTo(mutableSetOf()) { it.implementationName },
    )
    assertTrue(resolution.emptyContexts.isEmpty())

    // But parent contexts do not include child-scoped bindings beyond their own scope
    val parent = index.contextsFor(index.graphEntryAt(declarations.klass("ParentGraph"))!!).single()
    assertEquals(1, parent.chain.size)
    val otherParent =
      index.contextsFor(index.graphEntryAt(declarations.klass("OtherParentGraph"))!!).single()
    assertEquals(1, otherParent.chain.size)

    // Extension (and extension factory) accessors are creation points, not consumers
    declarations
      .filterIsInstance<KtProperty>()
      .filter { it.name == "childGraph" }
      .forEach { assertNull(index.consumerEntryAt(it)) }
    assertNull(index.consumerEntryAt(declarations.property("childFactory")))

    // The child aggregates only its own scope; parent-scope contributions are inherited
    val childQueryContexts = childContexts.map { index.queryContext(it)!! }
    assertTrue(childQueryContexts.all { index.contributionsFor(it).isEmpty() })
    assertEquals(
      listOf(1, 1),
      childQueryContexts.map { index.inheritedContributionsFor(it).size },
    )
    val parentQueryContext = index.queryContext(parent)!!
    assertEquals(1, index.contributionsFor(parentQueryContext).size)
    assertTrue(index.inheritedContributionsFor(parentQueryContext).isEmpty())
    val otherParentQueryContext = index.queryContext(otherParent)!!
    assertEquals(1, index.contributionsFor(otherParentQueryContext).size)
    assertTrue(index.inheritedContributionsFor(otherParentQueryContext).isEmpty())
  }

  fun testConsumerResolutionIsScopedToOwningGraph() {
    val file =
      myFixture.configureByText(
        "ScopedConsumers.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        abstract class OtherScope

        interface Repo

        @ContributesBinding(AppScope::class)
        @Inject
        class AppRepo : Repo

        @ContributesBinding(OtherScope::class)
        @Inject
        class OtherRepo : Repo

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val appRepo: Repo
        }

        @DependencyGraph(OtherScope::class)
        interface OtherGraph {
          val otherRepo: Repo
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val appRepo = index.consumerEntryAt(declarations.property("appRepo"))!!
    val appResolution = index.resolveConsumer(appRepo)
    assertEquals(
      listOf("AppRepo"),
      appResolution.uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(listOf("AppGraph"), appResolution.perContext.keys.map { it.graph.name })

    val otherRepo = index.consumerEntryAt(declarations.property("otherRepo"))!!
    val otherResolution = index.resolveConsumer(otherRepo)
    assertEquals(
      listOf("OtherRepo"),
      otherResolution.uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(listOf("OtherGraph"), otherResolution.perContext.keys.map { it.graph.name })
  }

  fun testConsumerResolutionDistinguishesUniformAndContextDependentBindings() {
    val file =
      myFixture.configureMetroFile(
        """
        abstract class OtherScope

        interface PartialRepo
        interface DifferentRepo
        interface StableRepo
        interface MissingRepo

        @Inject
        @ContributesBinding(AppScope::class)
        class PartialAppRepo : PartialRepo

        @Inject
        @ContributesBinding(AppScope::class)
        class DifferentAppRepo : DifferentRepo

        @Inject
        @ContributesBinding(OtherScope::class)
        class DifferentOtherRepo : DifferentRepo

        @Inject class StableRepoImpl : StableRepo

        @BindingContainer
        interface StableBindings {
          @Binds fun bindStable(impl: StableRepoImpl): StableRepo
        }

        @Inject
        class Consumer(
          val partialRepo: PartialRepo,
          val differentRepo: DifferentRepo,
          val stableRepo: StableRepo,
          val missingRepo: MissingRepo,
        )

        @DependencyGraph(AppScope::class, bindingContainers = [StableBindings::class])
        interface AppGraph {
          val consumer: Consumer
        }

        @DependencyGraph(OtherScope::class, bindingContainers = [StableBindings::class])
        interface OtherGraph {
          val consumer: Consumer
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    fun resolution(parameterName: String): ConsumerResolution {
      return index.resolveConsumer(index.consumerEntryAt(declarations.parameter(parameterName))!!)
    }

    fun implementationsByGraph(resolution: ConsumerResolution): Map<String?, List<String?>> {
      return resolution.perContext.entries.associate { (context, bindings) ->
        context.graph.name to bindings.map { it.implementationName }
      }
    }

    val partial = resolution("partialRepo")
    assertNull(partial.uniformBindings)
    assertEquals(
      listOf("PartialAppRepo"),
      partial.candidateBindings.map { it.implementationName },
    )
    assertEquals(setOf("OtherGraph"), partial.emptyContexts.mapTo(mutableSetOf()) { it.graph.name })
    assertEquals(
      mapOf(
        "AppGraph" to listOf("PartialAppRepo"),
        "OtherGraph" to emptyList(),
      ),
      implementationsByGraph(partial),
    )

    val different = resolution("differentRepo")
    assertNull(different.uniformBindings)
    assertEquals(
      setOf("DifferentAppRepo", "DifferentOtherRepo"),
      different.candidateBindings.mapTo(mutableSetOf()) { it.implementationName },
    )
    assertTrue(different.emptyContexts.isEmpty())

    val stable = resolution("stableRepo")
    assertEquals(
      listOf("StableRepoImpl"),
      stable.uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(2, stable.perContext.size)
    assertTrue(stable.emptyContexts.isEmpty())

    val missing = resolution("missingRepo")
    assertTrue(missing.uniformBindings.orEmpty().isEmpty())
    assertTrue(missing.candidateBindings.isEmpty())
    assertEquals(2, missing.perContext.size)
    assertEquals(2, missing.emptyContexts.size)
  }

  fun testGraphExtensionParentsOnlyComeFromExtensionCreationAccessors() {
    val file =
      myFixture.configureByText(
        "ExtensionParents.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.GraphExtension
        import dev.zacsweers.metro.Inject

        abstract class ChildScope

        interface Thing

        @ContributesBinding(AppScope::class)
        @Inject
        class RealThing : Thing

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
          class Token
          val thing: Thing
        }

        @DependencyGraph(AppScope::class)
        interface ParentGraph {
          val token: ChildGraph.Token
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()

    val child = index.graphEntryAt(declarations.klass("ChildGraph"))!!
    val childContexts = index.contextsFor(child)
    assertEquals(1, childContexts.size)
    assertEquals(1, childContexts.single().chain.size)

    val thing = index.consumerEntryAt(declarations.property("thing"))!!
    assertTrue(index.resolveConsumer(thing).uniformBindings.orEmpty().isEmpty())
  }

  fun testLibraryContributionHintsCanContributeSameProviderToMultipleScopes() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibMultiScopeHints.kt",
          """
          package test

          import dev.zacsweers.metro.AppScope
          import dev.zacsweers.metro.DependencyGraph
          import libtest.LibDual
          import libtest.LibScope

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val appDual: LibDual
          }

          @DependencyGraph(LibScope::class)
          interface LibGraph {
            val libDual: LibDual
          }
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().index(file)
      val declarations = file.declarationsIncludingNested()

      val appContext =
        index.contextsFor(index.graphEntryAt(declarations.klass("AppGraph"))!!).single()
      val appDual = index.consumerEntryAt(declarations.property("appDual"))!!
      assertEquals(
        listOf("LibDualImpl"),
        index.bindingsFor(appDual, index.queryContext(appContext)!!).map {
          it.implementationName
        },
      )

      val libContext =
        index.contextsFor(index.graphEntryAt(declarations.klass("LibGraph"))!!).single()
      val libDual = index.consumerEntryAt(declarations.property("libDual"))!!
      assertEquals(
        listOf("LibDualImpl"),
        index.bindingsFor(libDual, index.queryContext(libContext)!!).map {
          it.implementationName
        },
      )
    }
  }

  fun testScopedBindingsRequireMatchingGraphScope() {
    val file =
      myFixture.configureByText(
        "Scoped.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.SingleIn

        abstract class OtherScope

        @SingleIn(AppScope::class)
        @Inject
        class Repo

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val appRepo: Repo
        }

        @DependencyGraph(OtherScope::class)
        interface OtherGraph {
          val otherRepo: Repo
        }

        @SingleIn(AppScope::class)
        @DependencyGraph
        interface ExplicitGraph {
          val explicitRepo: Repo
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = index.consumerEntryAt(declarations.property("appRepo"))!!

    // @DependencyGraph(AppScope::class) implicitly conveys @SingleIn(AppScope::class)
    val appContext =
      index.contextsFor(index.graphEntryAt(declarations.klass("AppGraph"))!!).single()
    assertEquals(
      listOf("injected class"),
      index.bindingsFor(consumer, index.queryContext(appContext)!!).map { it.label },
    )

    // A graph with a different scope is not a home for this binding
    val otherContext =
      index.contextsFor(index.graphEntryAt(declarations.klass("OtherGraph"))!!).single()
    assertTrue(index.bindingsFor(consumer, index.queryContext(otherContext)!!).isEmpty())

    // Explicitly declared scope annotations on the graph also count
    val explicitContext =
      index.contextsFor(index.graphEntryAt(declarations.klass("ExplicitGraph"))!!).single()
    assertEquals(
      listOf("injected class"),
      index.bindingsFor(consumer, index.queryContext(explicitContext)!!).map { it.label },
    )
  }

  fun testIndexIsEmptyWhenMetroDisabled() {
    project.setMetroOptions("enabled" to "false")
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    assertTrue(index.bindings.isEmpty())
    assertTrue(index.consumers.isEmpty())
    assertTrue(index.graphs.isEmpty())
  }
}
