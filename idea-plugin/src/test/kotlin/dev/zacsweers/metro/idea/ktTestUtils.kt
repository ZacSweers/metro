// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.util.WaitFor
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.index.FilePresentationBundle
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ConsumerResolution
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphComposition
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

internal fun KaGraphValidationResult.requireCompleted(): KaGraphValidationResult.Completed {
  return this as? KaGraphValidationResult.Completed
    ?: error("Expected completed validation, got ${javaClass.simpleName}")
}

internal fun Module.addMetroRuntimeLibrary() {
  ModuleRootModificationUtil.addModuleLibrary(
    this,
    "metro-runtime",
    listOf(VfsUtil.getUrlForLibraryRoot(metroRuntimeJar().toFile())),
    emptyList(),
  )
}

internal fun Module.addKotlinStdlibLibrary() {
  ModuleRootModificationUtil.addModuleLibrary(
    this,
    "kotlin-stdlib",
    listOf(VfsUtil.getUrlForLibraryRoot(kotlinStdlibJar().toFile())),
    emptyList(),
  )
}

private fun metroRuntimeJar(): Path {
  return System.getProperty("metroRuntime.classpath")
    ?.split(File.pathSeparator)
    ?.map { Path.of(it) }
    ?.single {
      val fileName = it.fileName.toString()
      fileName.startsWith("runtime-jvm-") && fileName.endsWith(".jar")
    } ?: error("Unable to get a valid classpath from 'metroRuntime.classpath' property")
}

private fun kotlinStdlibJar(): Path {
  return System.getProperty("kotlinStdlib.classpath")?.let(Path::of)
    ?: error("Unable to get a valid path from 'kotlinStdlib.classpath' property")
}

private const val LIB_FIXTURE_NAME = "metro-lib-fixture"

// The platform persists per-path jar index caches across test runs, which go stale when the
// fixture jar is rebuilt at the same path. Copy it to a unique temp path once per test JVM.
private val libFixtureJar: Path by lazy {
  val jar =
    System.getProperty("metroLibFixture.classpath")?.let(Path::of)
      ?: error("Unable to get a valid path from 'metroLibFixture.classpath' property")
  val copy = Files.createTempFile("metro-lib-fixture", ".jar")
  Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING)
  copy.toFile().deleteOnExit()
  copy
}

/**
 * Runs [body] with the compiled `libFixture` jar (Metro-annotated classes + contribution hints)
 * attached as a module library. Light fixtures reuse the module across tests, so the library is
 * removed afterwards to avoid leaking into other tests.
 */
internal fun Module.withMetroLibFixtureLibrary(
  withinProject: Boolean = false,
  body: () -> Unit,
) {
  val jar =
    if (withinProject) {
      // Simulates a binary produced under the project path. Project ownership alone must not make
      // internal hints visible without a formal friend/associated compilation relationship.
      val base = Path.of(checkNotNull(project.basePath) { "No project base path" })
      Files.createDirectories(base)
      val copy = Files.createTempFile(base, "metro-lib-fixture", ".jar")
      Files.copy(libFixtureJar, copy, StandardCopyOption.REPLACE_EXISTING)
      copy.toFile().deleteOnExit()
      copy
    } else {
      libFixtureJar
    }
  ModuleRootModificationUtil.addModuleLibrary(
    this,
    LIB_FIXTURE_NAME,
    listOf(VfsUtil.getUrlForLibraryRoot(jar.toFile())),
    emptyList(),
  )
  try {
    body()
  } finally {
    ModuleRootModificationUtil.updateModel(this) { model ->
      val table = model.moduleLibraryTable
      table.libraries.filter { it.name == LIB_FIXTURE_NAME }.forEach(table::removeLibrary)
    }
  }
}

internal fun Project.setMetroOptions(vararg options: Pair<String, String>) {
  val configuredOptions =
    if (options.any { (name, _) -> name == "enabled" }) {
      options.toList()
    } else {
      listOf("enabled" to "true") + options
    }
  KotlinCommonCompilerArgumentsHolder.getInstance(this).update {
    pluginOptions =
      configuredOptions.map { (name, value) -> "plugin:$PLUGIN_ID:$name=$value" }.toTypedArray()
  }
}

/** Removes the plugin arguments so tests can represent a project that does not use Metro. */
internal fun Project.clearMetroOptions() {
  KotlinCommonCompilerArgumentsHolder.getInstance(this).update { pluginOptions = null }
}

/**
 * Configures a Kotlin file with the `test` package and a `dev.zacsweers.metro.*` import prepended.
 * Fixture sources only need their declarations, plus any non-Metro imports.
 */
internal fun CodeInsightTestFixture.configureMetroFile(
  @Language("kotlin") source: String,
  fileName: String = "Test.kt",
): KtFile {
  val text = buildString {
    appendLine("package test")
    appendLine()
    appendLine("import dev.zacsweers.metro.*")
    appendLine()
    appendLine(source.trimIndent())
  }
  return configureByText(fileName, text) as KtFile
}

/** Waits outside the fixture's EDT read action for the current index generation. */
internal fun MetroResolutionService.awaitIndex(element: PsiElement): BindingIndex {
  return awaitCurrentIndex(element.project) { index(element) }
}

/** Waits for a module's current index when a fixture has no single query declaration. */
internal fun MetroResolutionService.awaitIndex(module: Module): BindingIndex {
  return awaitCurrentIndex(module.project) { index(module) }
}

/** Drains queued invalidations before returning the fixture's final index. */
private fun MetroResolutionService.awaitCurrentIndex(
  project: Project,
  query: () -> BindingIndex,
): BindingIndex {
  fun requestCurrentIndex(): BindingIndex {
    val result = CompletableFuture.supplyAsync {
      runBlocking {
        retryCancelledIndexBuild { smartReadAction(project) { query() } }
      }
    }
    PlatformTestUtil.waitForFuture(result, 30_000)
    return result.join()
  }

  requestCurrentIndex()
  val barrier = CompletableFuture.runAsync { runBlocking { awaitCoordinatorBarrier() } }
  PlatformTestUtil.waitForFuture(barrier, 30_000)
  return requestCurrentIndex()
}

// These one-shot queries keep fixture assertions concise. Production callers own a session for
// the complete operation so related queries share their cached contexts and plans.

/** Looks up a fixture consumer's visible bindings in a fresh session. */
internal fun BindingIndex.bindingsFor(consumer: ConsumerEntry): List<KaBinding> =
  withResolutionSession { session ->
    session.bindingsFor(consumer)
  }

/** Looks up a fixture consumer's bindings in one concrete graph context. */
internal fun BindingIndex.bindingsFor(
  consumer: ConsumerEntry,
  queryContext: GraphQueryContext,
): List<KaBinding> = withResolutionSession { session ->
  session.bindingsFor(consumer, queryContext)
}

/** Resolves one fixture consumer across its graph contexts. */
internal fun BindingIndex.resolveConsumer(consumer: ConsumerEntry): ConsumerResolution =
  withResolutionSession { session ->
    session.resolveConsumer(consumer)
  }

/** Looks up a fixture key with the validation plan's membership rules. */
internal fun BindingIndex.bindingsForKey(
  key: KaTypeKey,
  queryContext: GraphQueryContext,
): List<KaBinding> = withResolutionSession { session -> session.bindingsForKey(key, queryContext) }

/** Resolves a fixture's collected multibinding elements. */
internal fun BindingIndex.multibindingContributions(
  multibindingId: String,
  queryContext: GraphQueryContext,
): List<KaBinding> = withResolutionSession { session ->
  session.multibindingContributions(multibindingId, queryContext)
}

/** Collects the fixture bindings belonging to one graph context. */
internal fun BindingIndex.bindingsInContext(queryContext: GraphQueryContext): List<KaBinding> =
  withResolutionSession { session ->
    session.bindingsInContext(queryContext)
  }

/** Selects the actual fixture graph roots, including contributed interfaces. */
internal fun BindingIndex.accessorsFor(queryContext: GraphQueryContext): List<ConsumerEntry> =
  withResolutionSession { session ->
    session.accessorsFor(queryContext)
  }

/** Selects a fixture graph's surface for its exact parent path. */
internal fun BindingIndex.graphComposition(
  queryContext: GraphQueryContext,
  graph: KaGraphDeclaration = queryContext.graphContext.graph,
): GraphComposition = withResolutionSession { session ->
  session.graphComposition(queryContext, graph)
}

/** Finds child declarations created by a fixture's selected graph surface. */
internal fun BindingIndex.extensionsOf(queryContext: GraphQueryContext): List<KaGraphDeclaration> =
  withResolutionSession { session ->
    session.extensionsOf(queryContext)
  }

/** Expands a fixture graph's concrete parent paths. */
internal fun BindingIndex.contextsFor(graph: KaGraphDeclaration): List<GraphContext> =
  withResolutionSession { session ->
    session.contextsFor(graph)
  }

/** Captures a module-aware query view for one fixture graph context. */
internal fun BindingIndex.queryContext(context: GraphContext): GraphQueryContext? =
  withResolutionSession { session ->
    session.queryContext(context)
  }

/** Finds concrete fixture children of one parent path. */
internal fun BindingIndex.extensionContextsOf(parent: GraphContext): List<GraphContext> =
  withResolutionSession { session ->
    session.extensionContextsOf(parent)
  }

/** Selects contributions aggregated by the fixture graph's own scopes. */
internal fun BindingIndex.contributionsFor(
  queryContext: GraphQueryContext
): List<ContributionEntry> = withResolutionSession { session ->
  session.contributionsFor(queryContext)
}

/** Selects contributions inherited through the fixture graph's ancestor scopes. */
internal fun BindingIndex.inheritedContributionsFor(
  queryContext: GraphQueryContext
): List<ContributionEntry> = withResolutionSession { session ->
  session.inheritedContributionsFor(queryContext)
}

/** Finds fixture consumers of these declarations, optionally restricted to one graph path. */
internal fun BindingIndex.consumersFor(
  bindingEntries: Collection<KaBinding>,
  graphPath: GraphPath? = null,
): List<ConsumerEntry> = withResolutionSession { session ->
  session.consumersFor(bindingEntries, graphPath)
}

/** Selects one unambiguous fixture consumer at a declaration. */
internal fun BindingIndex.consumerEntryAt(element: KtElement): ConsumerEntry? =
  withResolutionSession { session ->
    session.consumerEntryAt(element)
  }

/** Starts collecting state before returning and keeps test callbacks on the publishing thread. */
internal fun <T> StateFlow<T>.collectInTest(onValue: (T) -> Unit): AutoCloseable {
  val scope = CoroutineScope(Dispatchers.Unconfined)
  val collection = scope.async(start = CoroutineStart.UNDISPATCHED) { collect(onValue) }
  return AutoCloseable {
    scope.cancel()
    runBlocking {
      try {
        collection.await()
      } catch (_: CancellationException) {
        // Closing the test's collection cancels its suspended state subscription.
      }
    }
  }
}

/** Pumps the EDT until a worker has finished, including its cancellation cleanup. */
internal fun Job.awaitTestCompletion() {
  val completed = CompletableFuture<Unit>()
  invokeOnCompletion { completed.complete(Unit) }
  PlatformTestUtil.waitForFuture(completed, 30_000)
}

/** Waits for a published bundle whose declaration anchors match the current PSI stamp. */
internal fun KtFile.awaitMetroPresentation(
  service: MetroResolutionService = project.service()
): FilePresentationBundle {
  var result: FilePresentationBundle? = null
  object : WaitFor(30_000) {
      override fun condition(): Boolean {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val expectedStamp = modificationStamp
        val bundle = service.presentationBundle(this@awaitMetroPresentation)
        val anchorsAreCurrent = bundle != null && bundle.anchorsAreCurrent(expectedStamp)
        if (!anchorsAreCurrent || modificationStamp != expectedStamp) return false
        result = bundle
        return true
      }
    }
    .assertCompleted("Metro should publish a presentation bundle with current declaration anchors")
  return checkNotNull(result)
}

internal fun KtFile.declarationsIncludingNested(): List<KtDeclaration> {
  val declarations = mutableListOf<KtDeclaration>()
  accept(
    object : KtTreeVisitorVoid() {
      override fun visitDeclaration(dcl: KtDeclaration) {
        declarations += dcl
        super.visitDeclaration(dcl)
      }
    }
  )
  return declarations
}

internal fun List<KtDeclaration>.function(name: String): KtNamedFunction {
  return filterIsInstance<KtNamedFunction>().single { it.name == name }
}

internal fun List<KtDeclaration>.property(name: String): KtProperty {
  return filterIsInstance<KtProperty>().single { it.name == name }
}

internal fun List<KtDeclaration>.klass(name: String): KtClass {
  return filterIsInstance<KtClass>().single { it.name == name }
}

internal fun List<KtDeclaration>.obj(name: String): KtObjectDeclaration {
  return filterIsInstance<KtObjectDeclaration>().single { it.name == name }
}

internal fun List<KtDeclaration>.parameter(name: String): KtParameter {
  return PsiTreeUtil.findChildrenOfType(first().containingFile, KtParameter::class.java).single {
    it.name == name
  }
}

internal fun CodeInsightTestFixture.addCircuitStubs() {
  addFileToProject(
    "circuit/Stubs.kt",
    """
    package com.slack.circuit.runtime

    interface CircuitUiState

    interface Navigator

    interface CircuitContext
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/Screen.kt",
    """
    package com.slack.circuit.runtime.screen

    interface Screen
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/Ui.kt",
    """
    package com.slack.circuit.runtime.ui

    import com.slack.circuit.runtime.CircuitUiState

    interface Ui<S : CircuitUiState> {
      interface Factory
    }
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/Presenter.kt",
    """
    package com.slack.circuit.runtime.presenter

    import com.slack.circuit.runtime.CircuitUiState

    interface Presenter<S : CircuitUiState> {
      interface Factory
    }
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/CircuitInject.kt",
    """
    package com.slack.circuit.codegen.annotations

    import kotlin.reflect.KClass

    annotation class CircuitInject(val screen: KClass<*>, val scope: KClass<*>)
    """
      .trimIndent(),
  )
  addFileToProject(
    "compose/Modifier.kt",
    """
    package androidx.compose.ui

    interface Modifier
    """
      .trimIndent(),
  )
}
