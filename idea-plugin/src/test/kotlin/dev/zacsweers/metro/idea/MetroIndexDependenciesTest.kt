// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.SourceClassDependencies
import dev.zacsweers.metro.idea.index.restoreClassType
import dev.zacsweers.metro.idea.index.typeSnapshot
import dev.zacsweers.metro.idea.model.DeclarationResolutionScope
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeArgumentSnapshot
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.graphTypeKey
import dev.zacsweers.metro.idea.model.multibindingId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.types.Variance

/**
 * Covers the seal-facing index enrichment: dependency keys on bindings, contextual keys on
 * consumers, map key values, and the pull-based per-graph queries.
 */
class MetroIndexDependenciesTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.enableImmediateAutomaticRefresh()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
  }

  fun testNewObjectRefreshesAnUnresolvedGraphAccessor() {
    val graph =
      myFixture.configureMetroFile(
        """
      @DependencyGraph interface AppGraph { val registry: NewRegistry }
      """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(graph)
    val accessor = graph.declarationsIncludingNested().property("registry")
    val missing = initial.consumerEntryAt(accessor)!!
    assertTrue(missing.key.type.isError)
    assertTrue(initial.resolveConsumer(missing).uniformBindings.orEmpty().isEmpty())

    myFixture.addFileToProject("test/NewRegistry.kt", "package test; object NewRegistry")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val updated = service.awaitIndex(graph)
    val consumer = updated.consumerEntryAt(accessor)!!
    val binding = updated.resolveConsumer(consumer).uniformBindings.orEmpty().single()
    assertTrue(binding is KaBinding.ConstructorInjected && binding.isObject)
  }

  fun testNewObjectRefreshesAnUnresolvedGenericArgument() {
    val graph =
      myFixture.configureMetroFile(
        """
      @Inject class Box<T>(val value: T)
      @DependencyGraph interface AppGraph { val box: Box<NewRegistry> }
      """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(graph)
    val accessor = graph.declarationsIncludingNested().property("box")
    val missing = initial.consumerEntryAt(accessor)!!
    assertFalse(missing.key.type.isError)
    assertTrue(missing.key.type.typeArguments.single().type!!.isError)
    assertTrue(initial.resolveConsumer(missing).uniformBindings.orEmpty().isEmpty())

    myFixture.addFileToProject("test/NewRegistry.kt", "package test; object NewRegistry")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val updated = service.awaitIndex(graph)
    val consumer = updated.consumerEntryAt(accessor)!!
    val binding =
      updated.resolveConsumer(consumer).uniformBindings.orEmpty().single()
        as KaBinding.ConstructorInjected
    assertEquals("test.Box<test.NewRegistry>", binding.typeKey.renderedType)
    assertEquals("test.NewRegistry", binding.constructorDependencies.single().typeKey.renderedType)
    assertTrue(
      updated.bindings.filterIsInstance<KaBinding.ConstructorInjected>().any {
        it.isObject && it.typeKey.renderedType == "test.NewRegistry"
      }
    )
  }

  fun testUnrelatedClassEditsPreserveAnUnresolvedGraphSnapshot() {
    val unrelated =
      myFixture.addFileToProject(
        "test/Unrelated.kt",
        "package test; class Unrelated { fun value() = 1 }",
      ) as KtFile
    val graph =
      myFixture.configureMetroFile(
        "@DependencyGraph interface AppGraph { val registry: NewRegistry }"
      )
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(graph)
    service.awaitIndex(unrelated)
    val initial = service.awaitIndex(graph)
    val accessor = graph.declarationsIncludingNested().property("registry")
    assertTrue(initial.consumerEntryAt(accessor)!!.key.type.isError)

    val documents = PsiDocumentManager.getInstance(project)
    val document = checkNotNull(documents.getDocument(unrelated))
    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(document.textLength, "\n// An unrelated comment")
    }
    documents.commitAllDocuments()
    assertSame(initial, service.awaitIndex(graph))

    WriteCommandAction.runWriteCommandAction(project) {
      val valueOffset = document.text.indexOf("= 1") + 2
      document.replaceString(valueOffset, valueOffset + 1, "2")
    }
    documents.commitAllDocuments()
    assertSame(initial, service.awaitIndex(graph))
  }

  fun testDifferentClassArrivalPreservesAnUnresolvedGraphSnapshot() {
    val unrelated =
      myFixture.addFileToProject("test/Unrelated.kt", "package test; class Unrelated") as KtFile
    val graph =
      myFixture.configureMetroFile(
        "@DependencyGraph interface AppGraph { val registry: NewRegistry }"
      )
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(graph)
    service.awaitIndex(unrelated)
    val initial = service.awaitIndex(graph)
    val accessor = graph.declarationsIncludingNested().property("registry")
    assertTrue(initial.consumerEntryAt(accessor)!!.key.type.isError)

    val documents = PsiDocumentManager.getInstance(project)
    val document = checkNotNull(documents.getDocument(unrelated))
    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(document.textLength, "\nclass DifferentRegistry")
    }
    documents.commitAllDocuments()
    assertSame(initial, service.awaitIndex(graph))
  }

  fun testImportedAliasArrivalRefreshesAnUnresolvedGraphAccessor() {
    checkQualifiedClassArrival(
      """
      import test.generated.NewRegistry as Registry
      @DependencyGraph interface AppGraph { val registry: Registry }
      """
    )
  }

  fun testQualifiedClassArrivalRefreshesAnUnresolvedGraphAccessor() {
    checkQualifiedClassArrival(
      "@DependencyGraph interface AppGraph { val registry: test.generated.NewRegistry }"
    )
  }

  fun testNestedObjectArrivalRefreshesAnUnresolvedGraphAccessor() {
    checkQualifiedClassArrival(
      "@DependencyGraph interface AppGraph { val registry: test.generated.NewRegistry.Nested }",
      declaration = "object NewRegistry { object Nested }",
      expectedType = "test.generated.NewRegistry.Nested",
    )
  }

  /** Checks missing type recovery after its source declaration appears. */
  private fun checkQualifiedClassArrival(
    source: String,
    declaration: String = "object NewRegistry",
    expectedType: String = "test.generated.NewRegistry",
  ) {
    val graph = myFixture.configureMetroFile(source)
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(graph)
    val accessor = graph.declarationsIncludingNested().property("registry")
    assertTrue(initial.consumerEntryAt(accessor)!!.key.type.isError)

    myFixture.addFileToProject(
      "test/generated/NewRegistry.kt",
      "package test.generated; $declaration",
    )
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val updated = service.awaitIndex(graph)
    val consumer = updated.consumerEntryAt(accessor)!!
    val binding = updated.resolveConsumer(consumer).uniformBindings.orEmpty().single()
    assertTrue(binding is KaBinding.ConstructorInjected && binding.isObject)
    assertEquals(expectedType, binding.typeKey.renderedType)
  }

  fun testOpaqueTypeErrorsRetryVisibleClassDeclarations() {
    val graph = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val pointers = SmartPointerManager.getInstance(project)
    val builder = SourceClassDependencies.Builder(pointers)
    // Inferred errors can hide the missing class behind another function's return type.
    val opaqueError = KaTypeSnapshot("<inferred error>", classId = null, isError = true)
    assertTrue(
      builder.recordErrorTypes(opaqueError, graph, pointers.createSmartPsiElementPointer(graph))
    )
    val dependencies = builder.build()

    val declaration =
      myFixture.addFileToProject("test/NewRegistry.kt", "package test; object NewRegistry")
        as KtFile
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(setOf(graph.virtualFile), dependencies.ownersForAvailableDeclarations(declaration))
  }

  fun testUnannotatedObjectChangesRefreshClassBindings() {
    checkUnannotatedClassChanges(resolveFromLibraries = true)
  }

  fun testUnannotatedObjectChangesRefreshClassBindingsWithoutLibraries() {
    checkUnannotatedClassChanges(resolveFromLibraries = false)
  }

  private fun checkUnannotatedClassChanges(resolveFromLibraries: Boolean) {
    val settings = MetroSettings.getInstance(project).state
    val previous = settings.resolveFromLibraries
    settings.resolveFromLibraries = resolveFromLibraries
    try {
      val registry =
        myFixture.addFileToProject("test/Registry.kt", "package test; object Registry") as KtFile
      val graph =
        myFixture.configureMetroFile(
          """
        @DependencyGraph interface AppGraph { val registry: Registry }
        """
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(graph)
      fun bindings(index: dev.zacsweers.metro.idea.model.BindingIndex): List<KaBinding> {
        val accessor = graph.declarationsIncludingNested().property("registry")
        return index.resolveConsumer(index.consumerEntryAt(accessor)!!).uniformBindings.orEmpty()
      }
      assertTrue(
        bindings(initial).single().let { it is KaBinding.ConstructorInjected && it.isObject }
      )
      val documents = PsiDocumentManager.getInstance(project)
      val document = checkNotNull(documents.getDocument(registry))
      WriteCommandAction.runWriteCommandAction(project) {
        document.setText("package test; class Registry")
      }
      documents.commitAllDocuments()
      val removedObject = service.awaitIndex(graph)
      assertNotSame(initial, removedObject)
      assertTrue(bindings(removedObject).isEmpty())

      WriteCommandAction.runWriteCommandAction(project) {
        document.setText("package test; object Registry")
      }
      documents.commitAllDocuments()
      val restored = service.awaitIndex(graph)
      assertTrue(
        bindings(restored).single().let { it is KaBinding.ConstructorInjected && it.isObject }
      )

      WriteCommandAction.runWriteCommandAction(project) { registry.delete() }
      val deleted = service.awaitIndex(graph)
      assertTrue(bindings(deleted).isEmpty())
    } finally {
      settings.resolveFromLibraries = previous
    }
  }

  private fun configure(): KtFile {
    return myFixture.configureMetroFile(
      """
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
    )
  }

  fun testProvidesFunctionParametersAreDependencies() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
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
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.function("bindService")).single()
    assertEquals("binds", entry.label)
    assertEquals(listOf("test.ServiceImpl"), entry.dependencies.map { it.typeKey.renderedType })
  }

  fun testInjectConstructorParametersAreDependencies() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.klass("Consumer")).single()
    assertEquals("injected class", entry.label)
    assertEquals(2, entry.dependencies.size)

    // Provider wrapper structure is preserved on the dependency's contextual key
    val providerDep = entry.dependencies[0]
    assertEquals("test.Service", providerDep.typeKey.renderedType)
    assertTrue(providerDep.wrappedType is WrappedType.Provider)
    assertTrue(providerDep.isDeferrable)

    // Multibinding ids are deduced from the requested key itself
    val setDep = entry.dependencies[1]
    assertEquals("kotlin.collections.Set<test.Analytics>", setDep.typeKey.renderedType)
    assertEquals("test.Analytics", setDep.multibindingId())
  }

  fun testTypeSnapshotsRestoreStarsVarianceAndNullability() = allowAnalysisOnEdt {
    module.addKotlinStdlibLibrary()
    val file =
      myFixture.configureMetroFile(
        """
      class Holder<T>
      typealias Text = String
      interface Types {
        val stars: Map<*, Text?>?
        val nested: Holder<Map<*, Text?>>
        val producer: Holder<out Text>
        val consumer: Holder<in Text>
      }
      """
      )
    val declarations = file.declarationsIncludingNested()
    for (name in listOf("stars", "nested", "producer", "consumer")) {
      val property = declarations.property(name)
      val snapshot =
        analyze(property) {
          typeSnapshot((property.symbol as KaCallableSymbol).returnType)
        }
      analyze(property) {
        val restored = restoreClassType(snapshot)
        assertNotNull(name, restored)
        val roundTrip = typeSnapshot(restored!!)
        assertEquals(name, snapshot.renderedType, roundTrip.renderedType)
        assertEquals(name, snapshot.isMarkedNullable, roundTrip.isMarkedNullable)
      }
      when (name) {
        "stars" -> {
          assertTrue(snapshot.isMarkedNullable)
          assertSame(KaTypeArgumentSnapshot.Star, snapshot.typeArguments[0])
          assertTrue(snapshot.typeArguments[1].type!!.isMarkedNullable)
        }
        "producer" ->
          assertEquals(
            Variance.OUT_VARIANCE,
            (snapshot.typeArguments.single() as KaTypeArgumentSnapshot.Typed).variance,
          )
        "consumer" ->
          assertEquals(
            Variance.IN_VARIANCE,
            (snapshot.typeArguments.single() as KaTypeArgumentSnapshot.Typed).variance,
          )
      }
    }
  }

  fun testBindsReceiverKeepsItsQualifier() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service
        class ServiceImpl : Service

        interface ServiceBindings {
          @Binds fun @receiver:Named("function") ServiceImpl.bindFunction(): Service
          @Binds val @receiver:Named("property") ServiceImpl.bindProperty: Service
          @Binds fun bindParameter(@Named("parameter") impl: ServiceImpl): Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    for ((declaration, qualifier) in
      listOf(
        declarations.function("bindFunction") to "function",
        declarations.property("bindProperty") to "property",
        declarations.function("bindParameter") to "parameter",
      )) {
      val binding = index.bindingEntriesAt(declaration).single()
      assertEquals(
        "@Named(name = \"$qualifier\") ServiceImpl",
        binding.dependencies.single().typeKey.render(short = true),
      )
    }
  }

  fun testContributedBindingAliasesItsOwnInjectBinding() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val entries = index.bindingEntriesAt(declarations.klass("RealHttpApi"))
    val contributed = entries.single { it.label == "contributed binding" }
    assertEquals("test.HttpApi", contributed.typeKey.renderedType)
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
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.klass("WidgetFactory")).single()
    assertEquals("assisted factory", entry.label)
    // @Assisted id is supplied at create() time; only the graph-provided param remains
    assertEquals(listOf("test.HttpApi"), entry.dependencies.map { it.typeKey.renderedType })
  }

  fun testMapKeyValuesAreCaptured() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
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
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val providerParam = index.consumerEntryAt(declarations.parameter("serviceProvider"))!!
    assertEquals("test.Service", providerParam.key.renderedType)
    assertTrue(providerParam.contextKey.wrappedType is WrappedType.Provider)

    val setParam = index.consumerEntryAt(declarations.parameter("analytics"))!!
    assertEquals("test.Analytics", setParam.multibindingId)
    assertEquals(
      setParam.multibindingId,
      setParam.contextKey.multibindingId(),
    )
  }

  fun testSealFacingQueries() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val context = index.contextsFor(graph).single()
    val queryContext = index.queryContext(context)!!

    // Direct key lookup respects graph membership
    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    val serviceBindings = index.bindingsForKey(serviceParam.key, queryContext)
    assertEquals(listOf("binds"), serviceBindings.map { it.label })

    // Multibinding contributions resolve by multibinding id
    val contributions = index.multibindingContributions("test.Analytics", queryContext)
    assertEquals(1, contributions.size)
    assertEquals("multibinding contribution", contributions.single().label)

    // Context-wide listing includes the contributed + provides bindings
    val allBindings = index.bindingsInContext(queryContext)
    assertTrue(allBindings.any { it.label == "contributed binding" })
    assertTrue(allBindings.any { it.label == "provides" })

    // Roots: the graph's own accessors plus injector-driven member-inject keys
    val accessors = index.accessorsFor(graph)
    assertEquals(
      setOf("test.Consumer", "kotlin.String", "test.Tracker"),
      accessors.map { it.key.renderedType }.toSet(),
    )
  }

  fun testParallelSessionsDoNotShareStateOrExpandUnrelatedGraphs() {
    // Configure the editor after adding the second file so both sources are ready for indexing.
    myFixture.addFileToProject(
      "test/UnrelatedGraph.kt",
      """
      package test

      import dev.zacsweers.metro.DependencyGraph

      interface Missing

      @DependencyGraph
      interface UnrelatedGraph {
        val missing: Missing
      }
      """
        .trimIndent(),
    )
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = checkNotNull(index.consumerEntryAt(declarations.property("baseUrl")))
    assertNotNull(consumer.graphId)
    val unrelatedGraph = index.graphs.single { it.name == "UnrelatedGraph" }
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)

    fun resolveInNewSession(): CompletableFuture<Triple<List<String?>, List<String>, Boolean>> {
      return CompletableFuture.supplyAsync {
        ready.countDown()
        start.await()
        index.withResolutionSession { session ->
          val resolution = session.resolveConsumer(consumer)
          Triple(
            resolution.perContext.keys.map { it.graph.name },
            resolution.perContext.values.flatten().map { it.label },
            session.hasComputedContextsFor(unrelatedGraph),
          )
        }
      }
    }

    val first = resolveInNewSession()
    val second = resolveInNewSession()
    try {
      assertTrue(ready.await(30, TimeUnit.SECONDS))
    } finally {
      start.countDown()
    }
    PlatformTestUtil.waitForFuture(first, 30_000)
    PlatformTestUtil.waitForFuture(second, 30_000)
    val firstResult = first.join()
    val secondResult = second.join()

    assertEquals(firstResult, secondResult)
    assertEquals(listOf("AppGraph"), firstResult.first)
    assertEquals(listOf("provides"), firstResult.second)
    assertFalse(firstResult.third)
  }

  fun testGraphOwnedConsumerIncludesDescendantContextsWithoutExpandingUnrelatedGraphs() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val value: String
          val child: ChildGraph

          @Provides fun provideValue(): String = "app"
        }

        @GraphExtension
        interface ChildGraph

        @DependencyGraph
        interface UnrelatedGraph {
          val unrelated: Long
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = checkNotNull(index.consumerEntryAt(declarations.property("value")))
    val unrelatedGraph = index.graphs.single { it.name == "UnrelatedGraph" }

    index.withResolutionSession { session ->
      val resolution = session.resolveConsumer(consumer)

      assertEquals(
        listOf("AppGraph", "ChildGraph"),
        resolution.perContext.keys.map { it.graph.name },
      )
      assertEquals(
        listOf(listOf("provides"), listOf("provides")),
        resolution.perContext.values.map { bindings -> bindings.map { it.label } },
      )
      assertFalse(session.hasComputedContextsFor(unrelatedGraph))
    }
  }

  fun testValidationPlansAreReusedOnlyWithinTheirSession() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val session = index.createResolutionSession()
    val query = checkNotNull(session.queryContext(session.contextsFor(graph).single()))
    var scopeChecks = 0
    val countedQuery =
      GraphQueryContext(
        query.graphContext,
        query.graphModule,
        DeclarationResolutionScope { element ->
          scopeChecks++
          query.resolutionScope.contains(element)
        },
        query.containers,
      )

    val plan = session.validationPlan(countedQuery)
    val initialScopeChecks = scopeChecks
    assertTrue(initialScopeChecks > 0)
    // Empty key buckets isolate the plan work from per-candidate membership checks.
    val graphKey = checkNotNull(graph.graphTypeKey())
    repeat(3) {
      assertSame(plan, session.validationPlan(countedQuery))
      assertTrue(session.bindingsForKey(graphKey, countedQuery).isEmpty())
      assertTrue(session.multibindingContributions("unindexed", countedQuery).isEmpty())
    }
    assertEquals(initialScopeChecks, scopeChecks)

    val otherSession = index.createResolutionSession()
    val otherQuery =
      checkNotNull(otherSession.queryContext(otherSession.contextsFor(graph).single()))
    val otherPlan = otherSession.validationPlan(otherQuery)
    assertNotSame(plan, otherPlan)
    assertSame(otherPlan, otherSession.validationPlan(otherQuery))
  }

  fun testValidationPlansKeepCustomQueryScopesSeparate() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val consumerBinding = index.bindings.single { it.typeKey.renderedType == "test.Consumer" }
    val session = index.createResolutionSession()
    val query = checkNotNull(session.queryContext(session.contextsFor(graph).single()))
    val hiddenQuery =
      GraphQueryContext(
        query.graphContext,
        query.graphModule,
        DeclarationResolutionScope { false },
        query.containers,
      )

    val visiblePlan = session.validationPlan(query)
    val hiddenPlan = session.validationPlan(hiddenQuery)
    assertNotSame(visiblePlan, hiddenPlan)
    assertTrue(session.bindingsForKey(consumerBinding.typeKey, query).isNotEmpty())
    assertTrue(session.bindingsForKey(consumerBinding.typeKey, hiddenQuery).isEmpty())
    assertSame(visiblePlan, session.validationPlan(query))
    assertSame(hiddenPlan, session.validationPlan(hiddenQuery))
  }

  fun testCanceledValidationPlanCanRetryInTheSameSession() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val consumerBinding = index.bindings.single { it.typeKey.renderedType == "test.Consumer" }
    val session = index.createResolutionSession()
    val query = checkNotNull(session.queryContext(session.contextsFor(graph).single()))
    val cancellation = ProcessCanceledException()
    var shouldCancel = true
    val interruptibleQuery =
      GraphQueryContext(
        query.graphContext,
        query.graphModule,
        DeclarationResolutionScope { element ->
          if (shouldCancel) throw cancellation
          query.resolutionScope.contains(element)
        },
        query.containers,
      )

    try {
      session.validationPlan(interruptibleQuery)
      fail("Expected validation plan construction to be canceled")
    } catch (failure: ProcessCanceledException) {
      assertSame(cancellation, failure)
    }

    shouldCancel = false
    val completed = session.validationPlan(interruptibleQuery)
    assertSame(completed, session.validationPlan(interruptibleQuery))
    assertTrue(session.bindingsForKey(consumerBinding.typeKey, interruptibleQuery).isNotEmpty())
  }

  fun testProvidesReceiverIsADependencyAndConsumer() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val provideTag = declarations.function("provideTag")
    val entry = index.bindingEntriesAt(provideTag).single()
    assertEquals("kotlin.Int", entry.typeKey.renderedType)
    assertEquals(listOf("test.Service"), entry.dependencies.map { it.typeKey.renderedType })

    // The receiver is a consumer site anchored at its type reference
    val receiverConsumer = index.consumerEntryAt(provideTag.receiverTypeReference!!)!!
    assertEquals("test.Service", receiverConsumer.key.renderedType)
  }

  fun testMemberInjectSitesFoldIntoClassBindingDependencies() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
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
    val index = project.service<MetroResolutionService>().awaitIndex(file)
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
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val injectFunction = declarations.function("inject")
    val injectorConsumer = index.consumerEntryAt(injectFunction)!!
    assertEquals("test.Tracker", injectorConsumer.key.renderedType)

    // Injector-driven keys participate as seal roots alongside the accessors
    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val rootKeys = index.accessorsFor(graph).map { it.key.renderedType }.toSet()
    assertEquals(setOf("test.Consumer", "kotlin.String", "test.Tracker"), rootKeys)
  }

  fun testTopLevelFunctionInjectionOriginatesAClassBinding() {
    project.setMetroOptions("enable-top-level-function-injection" to "true")
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject fun App(service: Service, @Assisted id: String) {
        }
        """,
        fileName = "App.kt",
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)

    // The generated class is keyed by the function's name in its package
    val binding = index.bindings.single { it.typeKey.renderedType == "test.App" }
    assertEquals("injected class", binding.label)
    // Assisted parameters move to the generated invoke, not the constructor
    assertEquals(listOf("test.Service"), binding.dependencies.map { it.typeKey.renderedType })

    // Its parameters consume like constructor parameters
    val declarations = file.declarationsIncludingNested()
    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    assertEquals("test.Service", serviceParam.key.renderedType)
  }

  fun testLowercaseInjectedFunctionUsesCapitalizedClassName() {
    project.setMetroOptions("enable-top-level-function-injection" to "true")
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject fun render(service: Service) {}
        """,
        fileName = "Render.kt",
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val binding = index.bindingEntriesAt(declarations.function("render")).single()
    val consumer = index.consumerEntryAt(declarations.parameter("service"))!!

    assertEquals("test.Render", binding.typeKey.renderedType)
    assertEquals("Render", binding.implementationName)
    assertEquals(binding.typeKey.type.classId, consumer.originClassId)
  }

  fun testTopLevelFunctionInjectionIsInertWhenDisabled() {
    // With default options the compiler generates nothing for top-level inject functions, so the
    // index must not surface a binding or consumers for them.
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject fun App(service: Service, @Assisted id: String) {
        }
        """,
        fileName = "App.kt",
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)

    assertTrue(index.bindings.none { it.typeKey.renderedType == "test.App" })
    val declarations = file.declarationsIncludingNested()
    assertNull(index.consumerEntryAt(declarations.parameter("service")))
  }

  fun testMultibindingAccessorResolvesItsContributions() {
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        abstract class Activity
        class MainActivity : Activity()

        @MapKey
        annotation class ActivityKey(val value: KClass<out Activity>)

        interface ActivityProviders {
          @Provides
          @IntoMap
          @ActivityKey(MainActivity::class)
          fun provideMainActivity(): Activity = MainActivity()
        }

        @DependencyGraph(bindingContainers = [ActivityProviders::class])
        interface AppGraph {
          val activityProviders: Map<KClass<out Activity>, () -> Activity>
        }
        """,
        fileName = "Activities.kt",
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val consumer = index.consumerEntryAt(declarations.property("activityProviders"))!!
    assertNotNull(consumer.multibindingId)
    val contributions =
      index.resolveConsumer(consumer).uniformBindings.orEmpty().filter {
        it.multibindingId != null
      }
    assertEquals(1, contributions.size)
    assertEquals(
      "provideMainActivity",
      (contributions.single().pointer.element as? org.jetbrains.kotlin.psi.KtNamedFunction)?.name,
    )
  }

  fun testWrappedSetAccessorsMatchElementsContributions() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val providers: Set<Provider<String>>
          val lazies: Set<Lazy<String>>

          @Provides @ElementsIntoSet
          fun provideProviders(): Set<Provider<String>> = emptySet()

          @Provides @ElementsIntoSet
          fun provideLazies(): Set<Lazy<String>> = emptySet()
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    for ((accessor, provider) in
      listOf("providers" to "provideProviders", "lazies" to "provideLazies")) {
      val consumer = index.consumerEntryAt(declarations.property(accessor))!!
      val contribution = index.bindingEntriesAt(declarations.function(provider)).single()
      assertEquals(contribution.multibindingId, consumer.multibindingId)
      assertEquals(listOf(contribution), index.bindingsFor(consumer))
    }
  }

  fun testPlainSetContributionsStaySeparateFromWrappedSets() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val strings: Set<String>
          val providers: Set<Provider<String>>
          val lazies: Set<Lazy<String>>

          @Provides @IntoSet fun provideString(): String = "value"
          @Provides fun provideProviders(): Set<Provider<String>> = emptySet()
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val stringContribution = index.bindingEntriesAt(declarations.function("provideString")).single()
    val explicitProviders =
      index.bindingEntriesAt(declarations.function("provideProviders")).single()

    val strings = index.consumerEntryAt(declarations.property("strings"))!!
    val providers = index.consumerEntryAt(declarations.property("providers"))!!
    val lazies = index.consumerEntryAt(declarations.property("lazies"))!!
    assertEquals(listOf(stringContribution), index.bindingsFor(strings))
    assertEquals(listOf(explicitProviders), index.bindingsFor(providers))
    assertTrue(index.bindingsFor(lazies).isEmpty())
  }

  fun testMapValueWrappersShareContributions() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val values: Map<String, Int>
          val providers: Map<String, Provider<Int>>
          val lazies: Map<String, Lazy<Int>>

          @Provides @IntoMap @StringKey("key") fun provideValue(): Int = 1
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val contribution = index.bindingEntriesAt(declarations.function("provideValue")).single()

    for (accessor in listOf("values", "providers", "lazies")) {
      val consumer = index.consumerEntryAt(declarations.property(accessor))!!
      assertEquals(contribution.multibindingId, consumer.multibindingId)
      assertEquals(listOf(contribution), index.bindingsFor(consumer))
    }
  }

  fun testLibraryInjectBindingsCarryConstructorDependencies() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibClientWithDeps

          @Inject class LibConsumer(val client: LibClientWithDeps)
          """,
          fileName = "LibConsumer.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
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

  fun testInheritedAssistedFactoryFunctionKeepsSpecializedTargetDependencies() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Dependency

        @AssistedInject
        class Widget(@Assisted val id: String, val dependency: Dependency)

        interface BaseFactory<T> {
          fun create(id: String): T
        }

        @AssistedFactory
        interface WidgetFactory : BaseFactory<Widget>

        @DependencyGraph
        interface AppGraph {
          val factory: WidgetFactory
        }
        """,
        fileName = "InheritedFactory.kt",
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declaration = file.declarationsIncludingNested().klass("WidgetFactory")
    val factory = index.bindingEntriesAt(declaration).single() as KaBinding.AssistedFactory

    assertEquals("test.Widget", factory.targetTypeKey?.renderedType)
    assertEquals(
      listOf("test.Dependency"),
      factory.targetConstructorDependencies.map { it.typeKey.renderedType },
    )
  }
}
