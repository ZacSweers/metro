// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.hasAnyAnnotation
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

  fun postProcess(): SourceClassResolution {
    sourceClasses =
      SourceClassBindingPostProcessor(
        project,
        bindings,
        consumers,
        consumerOwnership,
        initialSourceClasses,
      )
    val resumed = sourceClasses.resumeBoundaries()
    bindings += resumed.addedBindings
    resolveLibraryInjectBindings(resumed.libraryRequests)
    return sourceClasses.snapshot()
  }

  /**
   * Demand-driven resolution of injected classes and assisted factories from compiled dependencies.
   * Source consumer sites and source/hint binding dependencies seed the same transitive traversal,
   * so generated providers also discover library dependencies without their own source consumers.
   */
  @OptIn(KaPlatformInterface::class)
  private fun resolveLibraryInjectBindings(resumedRequests: List<SourceClassRequest>) {
    val queue = ArrayDeque<LibraryInjectRequest>()
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      val classId = consumer.typeClassId ?: continue
      if (consumer.multibindingId != null) {
        continue
      }
      val containerOwners = consumerOwnership.owningGraphPointers(consumer)
      if (containerOwners == null) {
        val context = consumerOwnership.pointer(consumer).element ?: continue
        queue += LibraryInjectRequest(consumer.key, classId, context, direct = true)
      } else {
        for (owner in containerOwners) {
          val context = owner.element ?: continue
          queue += LibraryInjectRequest(consumer.key, classId, context, direct = true)
        }
      }
    }
    for (request in resumedRequests) {
      val context = request.context.element ?: continue
      val classId = request.key.type.classId ?: continue
      queue += LibraryInjectRequest(request.key, classId, context)
    }
    enqueueBindingDependencies(queue)
    if (queue.isEmpty()) return

    val visited = mutableSetOf<LibraryInjectRequestId>()
    val bindingIds =
      bindings.mapNotNullTo(mutableSetOf()) { binding ->
        val file = binding.pointer.virtualFile ?: return@mapNotNullTo null
        LibraryInjectBindingId(binding.typeKey, file)
      }
    val fileIndex = ProjectFileIndex.getInstance(project)
    while (queue.isNotEmpty()) {
      ProgressManager.checkCanceled()
      val request = queue.removeFirst()
      val module = KaModuleProvider.getModule(project, request.context, useSiteModule = null)
      if (!visited.add(LibraryInjectRequestId(request.key, module))) continue
      val source = sourceClasses.resolveFromBinary(request.key, request.context, request.direct)
      for (binding in source.addedBindings) {
        val file = binding.pointer.virtualFile ?: continue
        if (bindingIds.add(LibraryInjectBindingId(binding.typeKey, file))) bindings += binding
      }
      for (dependency in source.libraryRequests) {
        val context = dependency.context.element ?: continue
        val classId = dependency.key.type.classId ?: continue
        queue += LibraryInjectRequest(dependency.key, classId, context)
      }
      // A same-FQN source class in another module does not make this module's binary class
      // a source declaration. Fall through unless the exact request was actually handled.
      if (source.handled) continue
      val resolved =
        analyze(request.context) {
          val classSymbol = findClass(request.classId) as? KaNamedClassSymbol ?: return@analyze null
          val psi = classSymbol.psi ?: return@analyze null
          // Project sources were already swept; finding nothing there was authoritative
          val virtualFile = psi.containingFile?.virtualFile ?: return@analyze null
          if (fileIndex.isInContent(virtualFile)) return@analyze null

          val isAssistedFactory = classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)
          if (isAssistedFactory && !sourceClasses.isConcrete(request.key)) return@analyze null
          val binding =
            resolveClassBinding(classSymbol, request.key, options, pointerManager)
              ?: return@analyze null
          ResolvedLibraryBinding(
            LibraryInjectBindingId(binding.typeKey, virtualFile),
            binding,
          )
        }
      if (resolved == null) continue
      if (bindingIds.add(resolved.id)) bindings += resolved.binding
      if (!sourceClasses.expandClassBinding(resolved.binding, request.context, request.direct)) {
        continue
      }
      for (dependency in resolved.binding.dependencies) {
        ProgressManager.checkCanceled()
        val key = dependency.typeKey
        val classId = key.type.classId ?: continue
        queue += LibraryInjectRequest(key, classId, request.context)
      }
    }
  }

  /**
   * Hint-created providers have no source consumer entry, so their dependencies seed lookup too.
   */
  @OptIn(KaPlatformInterface::class)
  private fun enqueueBindingDependencies(queue: ArrayDeque<LibraryInjectRequest>) {
    val fileIndex = ProjectFileIndex.getInstance(project)
    val useSites = sourceUseSitesByModule(project, graphs, contributions, consumers)
    val seededFactoryUseSites =
      if (sourceClassUseSites.isEmpty()) null
      else {
        Collections.newSetFromMap(
          IdentityHashMap<Map<KaModule, SmartPsiElementPointer<out KtElement>>, Boolean>()
        )
      }
    val scopes = HashMap<KaModule, DeclarationResolutionScope>()
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      if (binding.dependencies.isEmpty()) continue
      // Graph member parameters already have consumers with their selected graph owners.
      if (binding.ownerGraphId != null) continue
      val declaration = binding.pointer.element ?: continue
      val virtualFile = binding.pointer.virtualFile ?: continue
      if (fileIndex.isInContent(virtualFile)) {
        // Ordinary source providers/injectables already contributed their parameter consumers.
        // Generated providers and concrete generic classes can own specialized dependencies.
        // Their requests retain the module where those concrete types are used.
        val needsSourceSeed =
          binding is KaBinding.AssistedFactory ||
            binding is KaBinding.ConstructorInjected ||
            binding is KaBinding.Provided && binding.isClassContribution ||
            binding is KaBinding.Alias && binding.isClassContribution
        if (!needsSourceSeed) continue
        if (binding is KaBinding.AssistedFactory || binding is KaBinding.ConstructorInjected) {
          val requestingModules = sourceClassUseSites[binding]
          if (requestingModules != null && seededFactoryUseSites?.add(requestingModules) == false) {
            continue
          }
          if (!requestingModules.isNullOrEmpty()) {
            for (pointer in requestingModules.values) {
              val context = pointer.element ?: continue
              if (!sourceClasses.mayExpandSourceBinding(binding, context)) continue
              enqueueDependencies(binding, context, queue)
            }
            continue
          }
        }
        val context = declaration as? KtElement ?: continue
        if (
          (binding is KaBinding.AssistedFactory || binding is KaBinding.ConstructorInjected) &&
            !sourceClasses.mayExpandSourceBinding(binding, context)
        )
          continue
        enqueueDependencies(binding, context, queue)
        continue
      }

      for ((module, context) in useSites) {
        ProgressManager.checkCanceled()
        val availability = binding.hintAvailability
        if (availability != null && !availability.isVisibleFrom(module)) continue
        val resolutionScope =
          scopes.getOrPut(module) {
            val platformScope = KaResolutionScope.forModule(module)
            DeclarationResolutionScope(platformScope::contains)
          }
        if (!resolutionScope.contains(declaration)) continue
        enqueueDependencies(binding, context, queue)
      }
    }
  }

  private fun enqueueDependencies(
    binding: KaBinding,
    context: KtElement,
    queue: ArrayDeque<LibraryInjectRequest>,
  ) {
    for (dependency in binding.dependencies) {
      val key = dependency.typeKey
      val classId = key.type.classId ?: continue
      queue += LibraryInjectRequest(key, classId, context)
    }
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  private data class LibraryInjectRequest(
    val key: KaTypeKey,
    val classId: ClassId,
    val context: KtElement,
    val direct: Boolean = false,
  )

  private data class LibraryInjectRequestId(val key: KaTypeKey, val module: KaModule)

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
