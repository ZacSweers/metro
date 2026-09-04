// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.graph.GraphDeclarationExtractor
import dev.zacsweers.metro.idea.index.graph.GraphMemberExtractor
import dev.zacsweers.metro.idea.index.graph.graphExtensionFactoryTarget
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement

/** Follows binary child declarations and scans each newly reached aggregation scope once. */
@OptIn(KaPlatformInterface::class)
internal class LibraryGraphDiscovery(
  private val project: Project,
  private val options: MetroOptions,
  private val sourceGraphs: List<KaGraphDeclaration>,
  private val sourceContributions: List<ContributionEntry>,
  sourceConsumers: List<ConsumerEntry>,
  private val sourceInterfaces: List<GraphInterfaceSurface>,
) {
  private val pointerManager = SmartPointerManager.getInstance(project)
  private val fileIndex = ProjectFileIndex.getInstance(project)
  private val pendingScopes = linkedSetOf<ClassId>()
  private val requests = ArrayDeque<GraphRequest>()
  private val visitedRequests = hashSetOf<GraphRequestId>()
  private val readGraphs = hashMapOf<GraphDeclarationId, ReadGraph>()
  private val sourceReferences = sourceGraphs.flatMapTo(hashSetOf()) { it.selfReferences }
  private val bindings = mutableListOf<KaBinding>()
  private val consumers = mutableListOf<ConsumerEntry>()
  private val graphs = mutableListOf<KaGraphDeclaration>()
  private val factoryInputs = mutableListOf<FactoryInputEntry>()
  private val hintBindings = mutableListOf<KaBinding>()
  private val contributions = mutableListOf<ContributionEntry>()
  private val graphInterfaces = mutableListOf<GraphInterfaceSurface>()
  private val dependencies = SourceClassDependencies.Builder(pointerManager)
  private val scanner =
    LibraryContributionScanner(
      project,
      options,
      sourceGraphs,
      sourceContributions,
      sourceConsumers,
      ::enqueue,
      { file, context -> recordSourceFile(file, context) },
    )

  init {
    for (graph in sourceGraphs) pendingScopes += graph.scopeKeys
    for (contribution in sourceContributions) pendingScopes += contribution.scopeKeys
  }

  /** All requests retain a source use site until the enclosing snapshot read action completes. */
  fun discover(): LibraryGraphMetadata {
    for (graph in sourceGraphs) {
      val context = graph.pointer.element as? KtElement ?: continue
      enqueueMembers(graph, context)
      for (surface in graph.contributedInterfaces) {
        for (reference in surface.extensionCreations) enqueue(reference, context)
      }
    }
    for (contribution in sourceContributions) {
      val child = contribution.graphExtension ?: continue
      val context = contribution.pointer.element as? KtElement ?: continue
      enqueue(child, context)
    }
    while (pendingScopes.isNotEmpty() || requests.isNotEmpty()) {
      ProgressManager.checkCanceled()
      if (pendingScopes.isNotEmpty()) {
        val scopes = pendingScopes.toSet()
        pendingScopes.clear()
        val found = scanner.scan(scopes)
        hintBindings += found.bindings
        contributions += found.contributions
        graphInterfaces += found.graphInterfaces
      }
      val request = requests.removeFirstOrNull() ?: continue
      val graph = readGraph(request) ?: continue
      enqueueMembers(graph, request.context)
      // Source contributions can introduce more children into a newly discovered binary scope.
      for (surface in sourceInterfaces) {
        if (surface.contribution.scopeKeys.none { it in graph.scopeKeys }) continue
        surface.contribution.pointer.element?.containingFile?.let {
          recordSourceFile(it, request.context)
        }
        for (reference in surface.extensionCreations) enqueue(reference, request.context)
      }
    }
    return LibraryGraphMetadata(
      LibraryContributions(hintBindings, contributions, graphInterfaces),
      LibraryGraphDeclarations(graphs, bindings, consumers, factoryInputs),
      dependencies.build(),
    )
  }

  private fun enqueueMembers(graph: KaGraphDeclaration, context: KtElement) {
    for (reference in graph.extensionCreations) enqueue(reference, context)
  }

  private fun enqueue(reference: GraphReference, context: KtElement) {
    if (reference in sourceReferences) return
    val module = KaModuleProvider.getModule(project, context, useSiteModule = null)
    val owner = context.containingFile?.virtualFile
    if (!visitedRequests.add(GraphRequestId(reference, module, owner))) return
    requests += GraphRequest(reference, context)
  }

  /** Equal class names in another module cannot satisfy a declaration-file-qualified reference. */
  private fun readGraph(request: GraphRequest): KaGraphDeclaration? =
    analyze(request.context) {
      var symbol =
        findClass(request.reference.classId) as? KaNamedClassSymbol ?: return@analyze null
      val referencedFile = symbol.psi?.containingFile?.virtualFile ?: return@analyze null
      val expectedFile = request.reference.file
      if (expectedFile != null && expectedFile != referencedFile) return@analyze null
      val sourceFiles = linkedSetOf<PsiFile>()
      val recordFile: (PsiFile) -> Unit = { file ->
        if (recordSourceFile(file, request.context)) sourceFiles += file
      }
      if (symbol.hasAnyAnnotation(options.graphExtensionFactoryAnnotations)) {
        val factoryType = symbol.defaultType as? KaClassType ?: return@analyze null
        val childType =
          graphExtensionFactoryTarget(factoryType, options, recordFile) ?: return@analyze null
        symbol = childType.symbol as? KaNamedClassSymbol ?: return@analyze null
      }
      if (!symbol.hasAnyAnnotation(options.graphExtensionAnnotations)) return@analyze null
      val declaration = symbol.psi as? KtClassOrObject ?: return@analyze null
      val file = declaration.containingFile.virtualFile ?: return@analyze null
      if (fileIndex.isInContent(file)) return@analyze null
      val graphId = GraphDeclarationId(symbol.classId, file)
      val previous = readGraphs[graphId]
      if (previous != null) {
        for (dependency in previous.sourceFiles) recordFile(dependency)
        return@analyze previous.graph
      }
      val graphMembers =
        GraphMemberExtractor(options, pointerManager, bindings, recordFile, { _, _ -> }, {})
      val graphDeclarations =
        GraphDeclarationExtractor(
          options,
          pointerManager,
          graphMembers,
          consumers,
          ::addFactoryInput,
          recordFile,
          { _, _ -> },
          onInstanceBinding = bindings::add,
        )
      val graph = graphDeclarations.extract(this, declaration) ?: return@analyze null
      graphs += graph
      pendingScopes += graph.scopeKeys
      readGraphs[graphId] = ReadGraph(graph, sourceFiles)
      graph
    }

  /** Every source use site owns the source files read through its cached binary child. */
  private fun recordSourceFile(file: PsiFile, context: KtElement): Boolean {
    val virtualFile = file.virtualFile ?: return false
    if (!fileIndex.isInContent(virtualFile)) return false
    dependencies.record(file, context.containingFile?.virtualFile)
    return true
  }

  private fun addFactoryInput(input: FactoryInputEntry) {
    val instance = input.bindings.firstOrNull()
    if (instance is KaBinding.BoundInstance) bindings += instance
    factoryInputs += input
  }

  private class GraphRequest(val reference: GraphReference, val context: KtElement)

  private data class GraphRequestId(
    val reference: GraphReference,
    val module: KaModule,
    val owner: VirtualFile?,
  )

  private class ReadGraph(val graph: KaGraphDeclaration, val sourceFiles: Set<PsiFile>)
}

/** Separates hints from graph-owned declarations for source-only dependency seeding. */
internal class LibraryGraphMetadata(
  val contributions: LibraryContributions,
  val declarations: LibraryGraphDeclarations,
  val sourceDependencies: SourceClassDependencies,
)

/** Binary child declarations and raw input metadata retained by the library snapshot cache. */
internal class LibraryGraphDeclarations(
  val graphs: List<KaGraphDeclaration>,
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val factoryInputs: List<FactoryInputEntry>,
) {
  val isEmpty: Boolean
    get() = graphs.isEmpty()

  companion object {
    val EMPTY = LibraryGraphDeclarations(emptyList(), emptyList(), emptyList(), emptyList())
  }
}
