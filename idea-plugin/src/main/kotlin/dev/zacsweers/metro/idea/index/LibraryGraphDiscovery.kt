// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.graph.GraphDeclarationExtractor
import dev.zacsweers.metro.idea.index.graph.GraphMemberExtractor
import dev.zacsweers.metro.idea.index.graph.graphExtensionFactoryTarget
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
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
  private val requests = ArrayDeque<LibraryGraphRequest>()
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
    )

  init {
    for (graph in sourceGraphs) {
      pendingScopes += graph.scopeKeys
    }
    for (contribution in sourceContributions) {
      pendingScopes += contribution.scopeKeys
    }
  }

  /**
   * Expands a stable frontier at a time. Workers own extraction and dependency capture, and this
   * collector owns visited identities, scope discovery, and the order of all accepted metadata.
   */
  suspend fun discover(executor: SnapshotReadExecutor): LibraryGraphMetadata {
    val initial = executor.read { initialRequests() }
    dependencies.include(initial.dependencies)
    for (request in initial.references) {
      enqueue(request)
    }
    while (pendingScopes.isNotEmpty() || requests.isNotEmpty()) {
      ProgressManager.checkCanceled()
      scanPendingScopes(executor)
      val frontier = requests.toList()
      requests.clear()
      val previous = readGraphs.toMap()
      val captured =
        executor.map(frontier, ::describeRequest) { request -> readGraph(request, previous) }
      for (result in captured) {
        // The original queue scans a graph's newly reached scopes before accepting its next
        // request. Prefetched reads retain that order when their metadata is merged here.
        scanPendingScopes(executor)
        dependencies.include(result.dependencies)
        val graph = result.graph ?: continue
        val cached = readGraphs.putIfAbsent(graph.id, graph)
        val accepted = cached ?: graph
        if (cached == null) {
          graphs += graph.graph
          bindings += graph.bindings
          consumers += graph.consumers
          factoryInputs += graph.factoryInputs
          pendingScopes += graph.graph.scopeKeys
        }
        val edges = executor.read { followGraph(accepted, result.request) }
        dependencies.include(edges.dependencies)
        for (request in edges.references) {
          enqueue(request)
        }
      }
    }
    return LibraryGraphMetadata(
      LibraryContributions(hintBindings, contributions, graphInterfaces),
      LibraryGraphDeclarations(graphs, bindings, consumers, factoryInputs),
      dependencies.build(),
    )
  }

  /** New hint requests append behind requests already admitted from the current frontier. */
  private suspend fun scanPendingScopes(executor: SnapshotReadExecutor) {
    if (pendingScopes.isEmpty()) {
      return
    }
    val scopes = pendingScopes.toSet()
    pendingScopes.clear()
    val found = scanner.scan(scopes, executor)
    hintBindings += found.metadata.bindings
    contributions += found.metadata.contributions
    graphInterfaces += found.metadata.graphInterfaces
    dependencies.include(found.dependencies)
    for (request in found.references) {
      enqueue(request)
    }
  }

  /**
   * Captures source stamps with request pointers so a vanished context still invalidates the read.
   */
  private fun initialRequests(): GraphEdges {
    val result = mutableListOf<LibraryGraphRequest>()
    val capturedDependencies = SourceClassDependencies.Builder(pointerManager)
    fun add(reference: GraphReference, context: KtElement) {
      val file = context.containingFile
      capturedDependencies.recordContext(file)
      result += LibraryGraphRequest.capture(project, pointerManager, reference, context)
    }
    for (graph in sourceGraphs) {
      val context = graph.pointer.element as? KtElement ?: continue
      for (reference in graph.extensionCreations) {
        add(reference, context)
      }
      for (surface in graph.contributedInterfaces) {
        for (reference in surface.extensionCreations) {
          add(reference, context)
        }
      }
    }
    for (contribution in sourceContributions) {
      val child = contribution.graphExtension ?: continue
      val context = contribution.pointer.element ?: continue
      add(child, context)
    }
    return GraphEdges(result, capturedDependencies.build())
  }

  private fun enqueue(request: LibraryGraphRequest) {
    if (request.reference in sourceReferences) {
      return
    }
    val id = GraphRequestId(request.reference, request.module, request.owner)
    if (!visitedRequests.add(id)) {
      return
    }
    requests += request
  }

  private fun describeRequest(request: LibraryGraphRequest): IndexBuildFile =
    IndexBuildFile(
      request.reference.classId.asSingleFqName().asString(),
      request.reference.file?.presentableUrl ?: request.owner?.presentableUrl.orEmpty(),
      request.module.moduleDescription,
    )

  /** Equal class names in another module cannot satisfy a declaration-file-qualified reference. */
  private fun readGraph(
    request: LibraryGraphRequest,
    previous: Map<GraphDeclarationId, ReadGraph>,
  ): CapturedGraph {
    val context = request.context.element
    if (context == null) {
      return CapturedGraph(request, null, SourceClassDependencies.EMPTY)
    }
    val capturedDependencies = SourceClassDependencies.Builder(pointerManager)
    val sourceFiles = linkedSetOf<SmartPsiElementPointer<PsiFile>>()
    val recordFile: (PsiFile) -> Unit = { file ->
      val virtualFile = file.virtualFile
      if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
        capturedDependencies.record(file, request.owner)
        sourceFiles += pointerManager.createSmartPsiElementPointer(file)
      }
    }
    capturedDependencies.recordContext(context.containingFile)
    val graph =
      analyze(context) {
        var symbol =
          findClass(request.reference.classId) as? KaNamedClassSymbol ?: return@analyze null
        val referencedPsiFile = symbol.psi?.containingFile ?: return@analyze null
        recordFile(referencedPsiFile)
        val referencedFile = referencedPsiFile.virtualFile ?: return@analyze null
        val expectedFile = request.reference.file
        if (expectedFile != null && expectedFile != referencedFile) {
          return@analyze null
        }
        if (symbol.hasAnyAnnotation(options.graphExtensionFactoryAnnotations)) {
          val factoryType = symbol.defaultType as? KaClassType ?: return@analyze null
          val childType =
            graphExtensionFactoryTarget(factoryType, options, recordFile) ?: return@analyze null
          symbol = childType.symbol as? KaNamedClassSymbol ?: return@analyze null
        }
        if (!symbol.hasAnyAnnotation(options.graphExtensionAnnotations)) {
          return@analyze null
        }
        val declaration = symbol.psi as? KtClassOrObject ?: return@analyze null
        recordFile(declaration.containingFile)
        val file = declaration.containingFile.virtualFile ?: return@analyze null
        if (fileIndex.isInContent(file)) {
          return@analyze null
        }
        val graphId = GraphDeclarationId(symbol.classId, file)
        val cached = previous[graphId]
        if (cached != null && cached.dependencies.isCurrent()) {
          // Every source use site owns the source files read through its cached binary child.
          for (dependency in cached.sourceFiles) {
            dependency.element?.let(recordFile)
          }
          return@analyze cached
        }
        val graphBindings = mutableListOf<KaBinding>()
        val graphConsumers = mutableListOf<ConsumerEntry>()
        val graphFactoryInputs = mutableListOf<FactoryInputEntry>()
        val graphMembers =
          GraphMemberExtractor(options, pointerManager, graphBindings, recordFile, { _, _ -> }, {})
        val graphDeclarations =
          GraphDeclarationExtractor(
            options,
            pointerManager,
            graphMembers,
            graphConsumers,
            { input ->
              val instance = input.bindings.firstOrNull()
              if (instance is KaBinding.BoundInstance) {
                graphBindings += instance
              }
              graphFactoryInputs += input
            },
            recordFile,
            { _, _ -> },
            onInstanceBinding = graphBindings::add,
          )
        val declarationGraph = graphDeclarations.extract(this, declaration) ?: return@analyze null
        ReadGraph(
          graphId,
          declarationGraph,
          graphBindings,
          graphConsumers,
          graphFactoryInputs,
          sourceFiles.toList(),
          capturedDependencies.build(),
        )
      }
    return CapturedGraph(request, graph, capturedDependencies.build())
  }

  /**
   * The first accepted declaration owns graph edges. Concurrent duplicate reads can observe that
   * declaration from different modules, so replay the accepted graph's dependencies for each owner.
   */
  private fun followGraph(graph: ReadGraph, request: LibraryGraphRequest): GraphEdges {
    val capturedDependencies = SourceClassDependencies.Builder(pointerManager, graph.dependencies)
    val references = mutableListOf<LibraryGraphRequest>()
    val context = request.context.element
    if (context == null) {
      return GraphEdges(emptyList(), capturedDependencies.build())
    }
    capturedDependencies.recordContext(context.containingFile)
    val recordFile: (PsiFile) -> Unit = { file ->
      val virtualFile = file.virtualFile
      if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
        capturedDependencies.record(file, request.owner)
      }
    }
    for (dependency in graph.sourceFiles) {
      dependency.element?.let(recordFile)
    }
    for (reference in graph.graph.extensionCreations) {
      references += LibraryGraphRequest.capture(project, pointerManager, reference, context)
    }
    // Source contributions can introduce more children into a newly discovered binary scope.
    for (surface in sourceInterfaces) {
      if (surface.contribution.scopeKeys.none { it in graph.graph.scopeKeys }) {
        continue
      }
      surface.contribution.pointer.element?.containingFile?.let(recordFile)
      for (reference in surface.extensionCreations) {
        references += LibraryGraphRequest.capture(project, pointerManager, reference, context)
      }
    }
    return GraphEdges(references, capturedDependencies.build())
  }

  private data class GraphRequestId(
    val reference: GraphReference,
    val module: KaModule,
    val owner: VirtualFile?,
  )

  /** Cached graph data retains only detached declarations and stamped source-file pointers. */
  private class ReadGraph(
    val id: GraphDeclarationId,
    val graph: KaGraphDeclaration,
    val bindings: List<KaBinding>,
    val consumers: List<ConsumerEntry>,
    val factoryInputs: List<FactoryInputEntry>,
    val sourceFiles: List<SmartPsiElementPointer<PsiFile>>,
    val dependencies: SourceClassDependencies,
  )

  private class CapturedGraph(
    val request: LibraryGraphRequest,
    val graph: ReadGraph?,
    val dependencies: SourceClassDependencies,
  )

  private class GraphEdges(
    val references: List<LibraryGraphRequest>,
    val dependencies: SourceClassDependencies,
  )
}

/** Module and source-owner identities accompany each pointer-backed binary graph request. */
@OptIn(KaPlatformInterface::class)
internal class LibraryGraphRequest(
  val reference: GraphReference,
  val context: SmartPsiElementPointer<KtElement>,
  val module: KaModule,
  val owner: VirtualFile?,
) {
  companion object {
    /** Captures all identities under the same read access as the graph reference. */
    fun capture(
      project: Project,
      pointers: SmartPointerManager,
      reference: GraphReference,
      context: KtElement,
    ): LibraryGraphRequest =
      LibraryGraphRequest(
        reference,
        pointers.createSmartPsiElementPointer(context),
        KaModuleProvider.getModule(project, context, useSiteModule = null),
        context.containingFile?.virtualFile,
      )
  }
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
