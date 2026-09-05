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
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingResolutionSession
import dev.zacsweers.metro.idea.model.ClassBindingIdentity
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DeclarationResolutionScope
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/**
 * Resolves concrete class dependencies after source and binary graph members have been composed.
 * Source and library declarations share the same demand-driven expansion state.
 */
internal class LibraryIndexPostProcessor(
  private val project: Project,
  private val options: MetroOptions,
  private val bindings: MutableList<KaBinding>,
  private val consumers: List<ConsumerEntry>,
  private val graphs: List<KaGraphDeclaration>,
  private val contributions: List<ContributionEntry>,
  private val sourceClassUseSites: SourceClassUseSites,
  private val consumerOwnership: ConsumerOwnershipBundle,
  private val initialSourceClasses: SourceClassResolution,
) {
  private val pointerManager = SmartPointerManager.getInstance(project)

  private lateinit var sourceClasses: SourceClassBindingPostProcessor

  /**
   * Read workers return detached bindings; this caller owns traversal and shared expansion limits.
   */
  suspend fun postProcess(executor: SnapshotReadExecutor): SourceClassResolution {
    sourceClasses = executor.read {
      SourceClassBindingPostProcessor(
        project,
        bindings,
        consumers,
        consumerOwnership,
        initialSourceClasses,
      )
    }
    val resumed = sourceClasses.resumeBoundaries(executor)
    bindings += resumed.addedBindings
    resolveLibraryInjectBindings(resumed.libraryRequests, executor)
    return sourceClasses.snapshot()
  }

  /**
   * Demand-driven resolution of injected classes and assisted factories from compiled dependencies.
   * Source consumer sites and source/hint binding dependencies seed the same transitive traversal,
   * so generated providers also discover library dependencies without their own source consumers.
   */
  private suspend fun resolveLibraryInjectBindings(
    resumedRequests: List<SourceClassRequest>,
    executor: SnapshotReadExecutor,
  ) {
    val seeds = executor.read { initialRequests(resumedRequests) }
    sourceClasses.includeDependencies(seeds.dependencies)
    val queue = ArrayDeque(seeds.requests)
    for (seed in seeds.bindings) {
      if (seed.needsExpansion && !sourceClasses.mayExpandSourceBinding(seed.binding, seed.module)) {
        continue
      }
      enqueueDependencies(seed.binding, seed.module, seed.context, queue)
    }
    if (queue.isEmpty()) {
      return
    }

    val visited = mutableSetOf<SourceClassRequestId>()
    val bindingIds =
      bindings.mapNotNullTo(mutableSetOf()) { binding ->
        val file = binding.pointer.virtualFile ?: return@mapNotNullTo null
        LibraryInjectBindingId(binding.typeKey, file)
      }
    while (queue.isNotEmpty()) {
      ProgressManager.checkCanceled()
      val requests = buildList {
        while (queue.isNotEmpty() && size < executor.parallelism) {
          val request = queue.removeFirst()
          if (visited.add(request.id)) {
            add(request)
          }
        }
      }
      // Budget access stays on the collector because concreteness checks memoize type complexity.
      val lookups = requests.mapNotNull { request ->
        if (request.id in initialSourceClasses.resolvedRequests) {
          null
        } else {
          LibraryLookup(request, sourceClasses.isConcrete(request.key))
        }
      }
      val candidates = executor.map(lookups, ::describeLookup, ::resolveLibraryBinding)
      val candidatesByRequest =
        lookups.indices.associate { index ->
          lookups[index].request.id to candidates[index]
        }
      for (request in requests) {
        ProgressManager.checkCanceled()
        // Accept source and binary results in FIFO order. They share limits, so a later source
        // request must wait until an earlier binary request has consumed its expansion allowance.
        val candidate = candidatesByRequest[request.id]
        if (candidate != null) {
          sourceClasses.includeDependencies(candidate.dependencies)
        }
        val source = sourceClasses.resolveFromBinary(request, executor)
        for (binding in source.addedBindings) {
          val file = binding.pointer.virtualFile ?: continue
          if (bindingIds.add(LibraryInjectBindingId(binding.typeKey, file))) {
            bindings += binding
          }
        }
        queue += source.libraryRequests
        // A same-FQN source class in another module does not make this module's binary class
        // a source declaration. Fall through unless the exact request was actually handled.
        if (source.handled) {
          continue
        }
        val resolved = candidate?.binding ?: continue
        if (bindingIds.add(resolved.id)) {
          bindings += resolved.binding
        }
        if (!sourceClasses.expandClassBinding(resolved.binding, request.module, request.direct)) {
          continue
        }
        enqueueDependencies(resolved.binding, request.module, request.context, queue)
      }
    }
  }

  /** Each retry captures a fresh queue without mutating traversal or its expansion budget. */
  @OptIn(KaPlatformInterface::class)
  private fun initialRequests(resumedRequests: List<SourceClassRequest>): LibrarySeeds {
    val requests = mutableListOf<SourceClassRequest>()
    val dependencies = SourceClassDependencies.Builder(pointerManager)
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      consumer.pointer.element?.containingFile?.let { dependencies.recordContext(it) }
      if (consumer.typeClassId == null || consumer.multibindingId != null) {
        continue
      }
      val owners = consumerOwnership.owningGraphPointers(consumer)
      val pointers = owners ?: listOf(consumerOwnership.pointer(consumer))
      for (pointer in pointers) {
        val context = pointer.element ?: continue
        context.containingFile?.let { dependencies.recordContext(it) }
        val module = KaModuleProvider.getModule(project, context, useSiteModule = null)
        requests += SourceClassRequest(consumer.key, module, pointer, direct = true)
      }
    }
    for (request in resumedRequests) {
      request.context.element?.containingFile?.let { dependencies.recordContext(it) }
      requests += request
    }
    val bindings = bindingSeeds(dependencies)
    return LibrarySeeds(requests, bindings, dependencies.build())
  }

  /** Every outcome retains its input stamps so a later read cannot hide a stale prefetch. */
  private fun resolveLibraryBinding(lookup: LibraryLookup): CapturedLibraryBinding {
    val dependencies = SourceClassDependencies.Builder(pointerManager)
    val request = lookup.request
    val context = request.context.element
    if (context == null) {
      return CapturedLibraryBinding(null, dependencies.build())
    }
    val owner = context.containingFile?.virtualFile
    context.containingFile?.let(dependencies::recordContext)
    val classId = request.key.type.classId
    if (classId == null) {
      return CapturedLibraryBinding(null, dependencies.build())
    }
    val fileIndex = ProjectFileIndex.getInstance(project)
    val onDeclarationFile: (PsiFile) -> Unit = { file ->
      val virtualFile = file.virtualFile
      if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
        dependencies.record(file, owner)
      }
    }
    val resolved =
      analyze(context) {
        val classSymbol = findClass(classId) as? KaNamedClassSymbol ?: return@analyze null
        val psi = classSymbol.psi ?: return@analyze null
        // Project sources were already swept; finding nothing there was authoritative.
        val virtualFile = psi.containingFile?.virtualFile ?: return@analyze null
        if (fileIndex.isInContent(virtualFile)) {
          psi.containingFile?.let(onDeclarationFile)
          return@analyze null
        }
        val isAssistedFactory = classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)
        if (isAssistedFactory && !lookup.isConcrete) {
          return@analyze null
        }
        val binding =
          resolveClassBinding(classSymbol, request.key, options, pointerManager, onDeclarationFile)
            ?: return@analyze null
        ResolvedLibraryBinding(LibraryInjectBindingId(binding.typeKey, virtualFile), binding)
      }
    return CapturedLibraryBinding(resolved, dependencies.build())
  }

  /** Request identity stays useful while its context pointer is being restored after an edit. */
  private fun describeLookup(lookup: LibraryLookup): IndexBuildFile {
    val request = lookup.request
    val name = request.key.type.classId?.asFqNameString() ?: request.key.renderedType
    val path = request.context.virtualFile?.presentableUrl ?: name
    return IndexBuildFile(name, path, request.module.moduleDescription)
  }

  /**
   * Hint-created providers have no source consumer entry, so their dependencies seed lookup too.
   * Visibility is captured under read access before the collector applies source expansion limits.
   */
  @OptIn(KaPlatformInterface::class)
  private fun bindingSeeds(
    dependencies: SourceClassDependencies.Builder
  ): List<LibraryBindingSeed> {
    val seeds = mutableListOf<LibraryBindingSeed>()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val useSites = sourceUseSitesByModule(project, graphs, contributions, consumers)
    // Keep the selected context even if visibility filtering produces no requests or its pointer
    // disappears before a worker starts. That change must invalidate this captured seed set.
    for (context in useSites.values) {
      context.containingFile?.let { dependencies.recordContext(it) }
    }
    val seededFactoryUseSites =
      if (sourceClassUseSites.isEmpty()) {
        null
      } else {
        Collections.newSetFromMap(
          IdentityHashMap<Map<KaModule, SmartPsiElementPointer<out KtElement>>, Boolean>()
        )
      }
    val scopes = HashMap<KaModule, DeclarationResolutionScope>()
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      val declaration = binding.pointer.element ?: continue
      val virtualFile = binding.pointer.virtualFile ?: continue
      if (fileIndex.isInContent(virtualFile)) {
        // Seed dependencies were already captured in source metadata. This read only borrows the
        // declaration as a lookup context; source signatures govern its retained cache identity.
        declaration.containingFile?.let(dependencies::recordContext)
      }
      if (binding.dependencies.isEmpty()) {
        continue
      }
      // Graph member parameters already have consumers with their selected graph owners.
      if (binding.ownerGraphId != null) {
        continue
      }
      if (fileIndex.isInContent(virtualFile)) {
        // Ordinary source providers/injectables already contributed their parameter consumers.
        // Generated providers and concrete generic classes can own specialized dependencies.
        // Their requests retain the module where those concrete types are used.
        val needsSourceSeed =
          binding is KaBinding.AssistedFactory ||
            binding is KaBinding.ConstructorInjected ||
            binding is KaBinding.Provided && binding.isClassContribution ||
            binding is KaBinding.Alias && binding.isClassContribution
        if (!needsSourceSeed) {
          continue
        }
        val needsExpansion =
          binding is KaBinding.AssistedFactory || binding is KaBinding.ConstructorInjected
        if (needsExpansion) {
          val requestingModules = sourceClassUseSites[binding]
          if (requestingModules != null && seededFactoryUseSites?.add(requestingModules) == false) {
            continue
          }
          if (!requestingModules.isNullOrEmpty()) {
            for ((module, pointer) in requestingModules) {
              val context = pointer.element ?: continue
              context.containingFile?.let { dependencies.recordContext(it) }
              seeds += LibraryBindingSeed(binding, module, pointer, needsExpansion = true)
            }
            continue
          }
        }
        val context = declaration as? KtElement ?: continue
        val module = KaModuleProvider.getModule(project, context, useSiteModule = null)
        seeds += LibraryBindingSeed(binding, module, ptr(context), needsExpansion)
        continue
      }

      for ((module, context) in useSites) {
        ProgressManager.checkCanceled()
        val availability = binding.hintAvailability
        if (availability != null && !availability.isVisibleFrom(module)) {
          continue
        }
        val resolutionScope =
          scopes.getOrPut(module) {
            val platformScope = KaResolutionScope.forModule(module)
            DeclarationResolutionScope(platformScope::contains)
          }
        if (!resolutionScope.contains(declaration)) {
          continue
        }
        seeds += LibraryBindingSeed(binding, module, ptr(context), needsExpansion = false)
      }
    }
    return seeds
  }

  private fun enqueueDependencies(
    binding: KaBinding,
    module: KaModule,
    context: SmartPsiElementPointer<out KtElement>,
    queue: ArrayDeque<SourceClassRequest>,
  ) {
    for (dependency in binding.dependencies) {
      ProgressManager.checkCanceled()
      val key = dependency.typeKey
      if (key.type.classId == null) {
        continue
      }
      queue += SourceClassRequest(key, module, context)
    }
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  private class LibrarySeeds(
    val requests: List<SourceClassRequest>,
    val bindings: List<LibraryBindingSeed>,
    val dependencies: SourceClassDependencies,
  )

  private class LibraryBindingSeed(
    val binding: KaBinding,
    val module: KaModule,
    val context: SmartPsiElementPointer<out KtElement>,
    val needsExpansion: Boolean,
  )

  private class LibraryLookup(val request: SourceClassRequest, val isConcrete: Boolean)

  private class CapturedLibraryBinding(
    val binding: ResolvedLibraryBinding?,
    val dependencies: SourceClassDependencies,
  )

  private data class LibraryInjectBindingId(val key: KaTypeKey, val file: VirtualFile)

  private data class ResolvedLibraryBinding(
    val id: LibraryInjectBindingId,
    val binding: KaBinding,
  )
}

/** Source generic factories resolve dependencies from the modules that request their exact type. */
internal fun sourceAssistedFactoryUseSites(
  project: Project,
  bindings: List<KaBinding>,
  consumers: List<ConsumerEntry>,
  consumerOwnership: ConsumerOwnershipBundle,
): SourceClassUseSites {
  return SourceClassBindingPostProcessor(project, bindings, consumers, consumerOwnership)
    .resolveInitial()
    .classUseSites
}

/**
 * Graph owners captured once for dependency resolution in the owning modules. Equivalent rebuilt
 * consumers reuse these answers while the source library summary remains current.
 */
@OptIn(KaPlatformInterface::class)
internal class ConsumerOwnershipBundle
private constructor(
  private val pointersByGraphId: Map<GraphDeclarationId, SmartPsiElementPointer<out KtElement>>,
  private val pointersByIncludedContainer:
    Map<KaTypeKey, List<SmartPsiElementPointer<out KtElement>>>,
  private val graphOwnersByConsumer: Map<ConsumerOwnershipKey, FrozenConsumerOwners>,
) {
  private constructor(
    state: ConsumerOwnershipState
  ) : this(
    state.pointersByGraphId,
    state.pointersByIncludedContainer,
    state.graphOwnersByConsumer,
  )

  fun pointer(consumer: ConsumerEntry): SmartPsiElementPointer<out KtElement> {
    val graphId = consumer.graphId ?: return consumer.pointer
    return pointersByGraphId[graphId] ?: consumer.pointer
  }

  /** Returns the graph roots used to resolve an included container, with one entry per module. */
  fun includedContainerPointers(
    consumer: ConsumerEntry
  ): List<SmartPsiElementPointer<out KtElement>>? {
    if (consumer.graphId != null) return null
    val containerKey = consumer.includedContainerKey ?: return null
    return pointersByIncludedContainer[containerKey]
  }

  /**
   * Returns graph contexts for resolving [consumer], or null to use [pointer]. An empty list means
   * the consumer has no active graph owner.
   */
  fun owningGraphPointers(consumer: ConsumerEntry): List<SmartPsiElementPointer<out KtElement>>? {
    val graphId = consumer.graphId
    if (graphId != null) {
      if (graphId !in pointersByGraphId) return emptyList()
      return when (val owners = graphOwnersByConsumer[consumer.ownershipKey(graphId)]) {
        null -> null
        FrozenConsumerOwners.None -> emptyList()
        is FrozenConsumerOwners.GraphRoots -> owners.pointers
      }
    }
    return includedContainerPointers(consumer)
  }

  companion object {
    fun build(index: BindingIndex): ConsumerOwnershipBundle {
      return ConsumerOwnershipBundle(buildState(index))
    }

    private fun buildState(index: BindingIndex): ConsumerOwnershipState {
      return index.withResolutionSession { session ->
        ConsumerOwnershipBuilder(index, session).build()
      }
    }
  }
}

@OptIn(KaPlatformInterface::class)
private class ConsumerOwnershipBuilder(
  private val index: BindingIndex,
  private val session: BindingResolutionSession,
) {
  private val graphs = index.graphs
  private val graphsById = graphs.associateBy { it.declarationId }
  private val pointersByGraphId = graphs.associate { it.declarationId to it.pointer }

  fun build(): ConsumerOwnershipState {
    val rootPointersByGraphId = buildRootPointersByGraphId()
    val pointersByIncludedContainer = buildIncludedContainerPointers(rootPointersByGraphId)
    val graphOwnersByConsumer = linkedMapOf<ConsumerOwnershipKey, FrozenConsumerOwners>()
    val consumersByGraphId = linkedMapOf<GraphDeclarationId, MutableList<ConsumerEntry>>()
    for (consumer in index.consumers) {
      ProgressManager.checkCanceled()
      val graphId = consumer.graphId ?: continue
      consumersByGraphId.getOrPut(graphId) { mutableListOf() } += consumer
    }
    for ((graphId, consumers) in consumersByGraphId) {
      ProgressManager.checkCanceled()
      val graph = graphsById[graphId]
      if (graph == null) {
        for (consumer in consumers) {
          graphOwnersByConsumer[consumer.ownershipKey(graphId)] = FrozenConsumerOwners.None
        }
        continue
      }
      val contexts = session.contextsFor(graph).mapNotNull(session::queryContext)
      for (consumer in consumers) {
        ProgressManager.checkCanceled()
        val owners = ownerPointers(consumer, graphId, contexts)
        if (owners != null) graphOwnersByConsumer[consumer.ownershipKey(graphId)] = owners
      }
    }
    ProgressManager.checkCanceled()
    return ConsumerOwnershipState(
      pointersByGraphId.toMap(),
      pointersByIncludedContainer,
      graphOwnersByConsumer.toMap(),
    )
  }

  private fun buildRootPointersByGraphId():
    Map<GraphDeclarationId, List<SmartPsiElementPointer<out KtElement>>> {
    val needsExtensionRoots = graphs.any {
      it.isExtension && it.includedBindingContainers.isNotEmpty()
    }
    if (!needsExtensionRoots) return emptyMap()
    return buildMap {
      for (graph in graphs) {
        ProgressManager.checkCanceled()
        if (!graph.isExtension || graph.includedBindingContainers.isEmpty()) continue
        val roots = session.contextsFor(graph).map { it.rootGraph.pointer }.distinct()
        if (roots.isNotEmpty()) put(graph.declarationId, roots)
      }
    }
  }

  private fun buildIncludedContainerPointers(
    rootPointersByGraphId: Map<GraphDeclarationId, List<SmartPsiElementPointer<out KtElement>>>
  ): Map<KaTypeKey, List<SmartPsiElementPointer<out KtElement>>> {
    val pointers = linkedMapOf<KaTypeKey, MutableList<SmartPsiElementPointer<out KtElement>>>()
    val modulesByContainer = HashMap<KaTypeKey, MutableSet<KaModule>>()
    for (graph in graphs) {
      ProgressManager.checkCanceled()
      if (graph.includedBindingContainers.isEmpty()) continue
      val owners = rootPointersByGraphId[graph.declarationId] ?: listOf(graph.pointer)
      for (owner in owners) {
        val declaration = owner.element ?: continue
        val module =
          KaModuleProvider.getModule(declaration.project, declaration, useSiteModule = null)
        for (container in graph.includedBindingContainers) {
          val modules = modulesByContainer.getOrPut(container) { mutableSetOf() }
          if (!modules.add(module)) continue
          pointers.getOrPut(container) { mutableListOf() }.add(owner)
        }
      }
    }
    return pointers.mapValues { (_, values) -> values.toList() }
  }

  private fun ownerPointers(
    consumer: ConsumerEntry,
    graphId: GraphDeclarationId,
    contexts: List<GraphQueryContext>,
  ): FrozenConsumerOwners? {
    if (contexts.size == 1) {
      val context = contexts.single()
      if (!session.isConsumerInContext(consumer, context)) return FrozenConsumerOwners.None
      if (context.graphContext.rootGraph.declarationId == graphId) return null
      return FrozenConsumerOwners.GraphRoots(listOf(context.graphContext.rootGraph.pointer))
    }
    val owners = mutableListOf<SmartPsiElementPointer<out KtElement>>()
    val modules = mutableSetOf<KaModule>()
    for (context in contexts) {
      ProgressManager.checkCanceled()
      if (!session.isConsumerInContext(consumer, context)) continue
      if (modules.add(context.graphModule)) owners += context.graphContext.rootGraph.pointer
    }
    if (owners.isEmpty()) return FrozenConsumerOwners.None
    return FrozenConsumerOwners.GraphRoots(owners)
  }
}

private class ConsumerOwnershipState(
  val pointersByGraphId: Map<GraphDeclarationId, SmartPsiElementPointer<out KtElement>>,
  val pointersByIncludedContainer: Map<KaTypeKey, List<SmartPsiElementPointer<out KtElement>>>,
  val graphOwnersByConsumer: Map<ConsumerOwnershipKey, FrozenConsumerOwners>,
)

/** Keeps inherited specializations, contribution selection, and implemented requests separate. */
private data class ConsumerOwnershipKey(
  val graphId: GraphDeclarationId,
  val contextKey: KaContextualTypeKey,
  val originClassId: ClassId?,
  val contribution: GraphReference?,
  val requestKind: ConsumerEntry.GraphRequestKind?,
  val isOptional: Boolean,
  val source: ConsumerOwnershipSource,
)

/** Matches regenerated graph consumers to the ownership retained by their source summary. */
private fun ConsumerEntry.ownershipKey(graphId: GraphDeclarationId): ConsumerOwnershipKey {
  val sourceFile = pointer.virtualFile
  val needsDeclarationIdentity = graphRequestKind != null && !isOptional
  val declaration = if (needsDeclarationIdentity || sourceFile == null) pointer else null
  return ConsumerOwnershipKey(
    graphId,
    contextKey,
    originClassId,
    graphContribution,
    graphRequestKind,
    isOptional,
    ConsumerOwnershipSource(sourceFile, declaration),
  )
}

/**
 * Graph-owned dependency sites share their file's visibility. Required graph requests retain
 * declaration identity so implemented accessors remain distinct across reparses and offset changes.
 */
private class ConsumerOwnershipSource(
  private val file: VirtualFile?,
  private val pointer: SmartPsiElementPointer<out KtElement>?,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ConsumerOwnershipSource || file != other.file) return false
    if (pointer === other.pointer) return true
    if (pointer == null || other.pointer == null) return false
    return SmartPointerManager.getInstance(pointer.project)
      .pointToTheSameElement(pointer, other.pointer)
  }

  // Source offsets can change while a summary survives. The file keeps the hash stable.
  override fun hashCode(): Int = file.hashCode()
}

private sealed interface FrozenConsumerOwners {
  data object None : FrozenConsumerOwners

  class GraphRoots(val pointers: List<SmartPsiElementPointer<out KtElement>>) : FrozenConsumerOwners
}

/** Session-free source class groups that remain reusable when equivalent shards are rebuilt. */
internal class SourceClassUseSites(
  private val groups:
    Map<ClassBindingIdentity, Map<KaModule, SmartPsiElementPointer<out KtElement>>>
) {
  operator fun get(binding: KaBinding): Map<KaModule, SmartPsiElementPointer<out KtElement>>? {
    val virtualFile = binding.pointer.virtualFile ?: return null
    return groups[ClassBindingIdentity(binding.typeKey, binding.originClassId, virtualFile)]
  }

  fun isEmpty(): Boolean = groups.isEmpty()

  companion object {
    val EMPTY = SourceClassUseSites(emptyMap())
  }
}
