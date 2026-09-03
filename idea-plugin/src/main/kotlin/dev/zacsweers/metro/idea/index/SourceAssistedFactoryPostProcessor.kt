// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.ClassBindingIdentity
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import java.util.Collections
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/** A concrete request retains its compilation module without retaining an analysis session. */
internal data class SourceFactoryRequestId(val key: KaTypeKey, val module: KaModule)

internal class SourceFactoryRequest(
  val key: KaTypeKey,
  val module: KaModule,
  val context: SmartPsiElementPointer<out KtElement>,
  val direct: Boolean = false,
) {
  val id: SourceFactoryRequestId = SourceFactoryRequestId(key, module)
}

/** Immutable state that a binary-resolution pass can continue without repeating source work. */
internal class SourceFactoryResolution(
  val addedBindings: List<KaBinding.AssistedFactory>,
  val useSites: Map<ClassBindingIdentity, Map<KaModule, SmartPsiElementPointer<out KtElement>>>,
  val processedRequests: Set<SourceFactoryRequestId>,
  val resolvedRequests: Set<SourceFactoryRequestId>,
  val boundaryRequests: List<SourceFactoryRequest>,
  val budget: ClassBindingExpansionBudgetState,
) {
  val factoryUseSites: SourceAssistedFactoryUseSites = SourceAssistedFactoryUseSites(useSites)
  val incompleteBindings: Map<KaModule, Map<ClassBindingIdentity, String>>
    get() = budget.incompleteBindings
}

internal class SourceFactoryExpansion(
  val addedBindings: List<KaBinding.AssistedFactory>,
  val libraryRequests: List<SourceFactoryRequest>,
  val handled: Boolean = true,
)

/**
 * Owns the transitive source-factory closure after file shards have been merged. A declaration is
 * materialized once per concrete key, while module-qualified requests still propagate every actual
 * use site's classpath. Binary lookup continues this same state when it discovers a source factory.
 */
@OptIn(KaPlatformInterface::class)
internal class SourceAssistedFactoryPostProcessor(
  private val project: Project,
  bindings: List<KaBinding>,
  private val consumers: List<ConsumerEntry>,
  private val consumerOwnership: ConsumerOwnershipBundle,
  previous: SourceFactoryResolution? = null,
) {
  private val pointerManager = SmartPointerManager.getInstance(project)
  private val fileIndex = ProjectFileIndex.getInstance(project)
  private val factories = linkedMapOf<ClassBindingIdentity, KaBinding.AssistedFactory>()
  private val sourceFactoryClassIds = hashSetOf<ClassId>()
  private val sourceDeclarations = hashSetOf<Pair<ClassId, VirtualFile>>()
  private val addedBindings = previous?.addedBindings?.toMutableList() ?: mutableListOf()
  private val useSites =
    linkedMapOf<
      ClassBindingIdentity,
      MutableMap<KaModule, SmartPsiElementPointer<out KtElement>>,
    >()
  private val processed = previous?.processedRequests?.toMutableSet() ?: hashSetOf()
  private val resolved = previous?.resolvedRequests?.toMutableSet() ?: hashSetOf()
  private val bindingOnlySeeds = bindings.filter {
    it is KaBinding.Provided && it.isClassContribution ||
      it is KaBinding.Alias && it.isClassContribution
  }
  private val boundaries = linkedMapOf<SourceFactoryRequestId, SourceFactoryRequest>()
  private val queue = ArrayDeque<SourceFactoryRequest>()
  private val libraryRequests = mutableListOf<SourceFactoryRequest>()
  private val budget: ClassBindingExpansionBudget

  init {
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      if (binding !is KaBinding.AssistedFactory) continue
      val identity = binding.classBindingIdentity() ?: continue
      if (!fileIndex.isInContent(identity.virtualFile)) continue
      factories.putIfAbsent(identity, binding)
      identity.originClassId?.let {
        sourceFactoryClassIds += it
        sourceDeclarations += it to identity.virtualFile
      }
    }
    for (binding in addedBindings) {
      ProgressManager.checkCanceled()
      val identity = binding.classBindingIdentity() ?: continue
      factories.putIfAbsent(identity, binding)
      identity.originClassId?.let {
        sourceFactoryClassIds += it
        sourceDeclarations += it to identity.virtualFile
      }
    }
    previous?.useSites?.forEach { (identity, sites) -> useSites[identity] = LinkedHashMap(sites) }
    previous?.boundaryRequests?.forEach { boundaries[it.id] = it }
    budget = ClassBindingExpansionBudget(factories.keys, sourceDeclarations.size, previous?.budget)
    // Derived bindings from a previous pass are not newly written inputs. Explicit hint bindings
    // are, and can raise the boundary when binary resolution resumes a source-only snapshot.
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      val writtenKey = binding.writtenClassBudgetKey() ?: continue
      budget.includeWrittenKey(writtenKey, writtenKey.type.classId in sourceFactoryClassIds)
    }
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      budget.includeWrittenKey(consumer.key, consumer.typeClassId in sourceFactoryClassIds)
    }
  }

  fun resolveInitial(): SourceFactoryResolution {
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      if (consumer.multibindingId != null) continue
      val owners = consumerOwnership.owningGraphPointers(consumer)
      if (owners == null) {
        enqueue(consumer.key, consumerOwnership.pointer(consumer), direct = true)
      } else {
        for (owner in owners) enqueue(consumer.key, owner, direct = true)
      }
    }
    for (binding in bindingOnlySeeds) {
      ProgressManager.checkCanceled()
      val declaration = binding.pointer.element as? KtElement ?: continue
      val pointer = pointerManager.createSmartPsiElementPointer(declaration)
      for (dependency in binding.dependencies) enqueue(dependency.typeKey, pointer, direct = true)
    }
    drain()
    return snapshot()
  }

  /** Retry only previous boundaries; already-expanded source keys remain memoized. */
  fun resumeBoundaries(): SourceFactoryExpansion {
    val bindingStart = addedBindings.size
    val requestStart = libraryRequests.size
    for (request in boundaries.values.toList()) {
      processed.remove(request.id)
      queue += request
    }
    drain()
    return expansionSince(bindingStart, requestStart)
  }

  fun canResolve(classId: ClassId): Boolean = classId in sourceFactoryClassIds

  fun resolveFromBinary(
    key: KaTypeKey,
    context: KtElement,
    direct: Boolean,
  ): SourceFactoryExpansion {
    val bindingStart = addedBindings.size
    val requestStart = libraryRequests.size
    val id = enqueue(key, pointerManager.createSmartPsiElementPointer(context), direct)
    drain()
    return SourceFactoryExpansion(
      addedBindings.subList(bindingStart, addedBindings.size).toList(),
      libraryRequests.subList(requestStart, libraryRequests.size).toList(),
      id != null && id in resolved,
    )
  }

  /**
   * The binary queue shares these limits so crossing the source/library boundary cannot evade them.
   */
  fun expandClassBinding(
    binding: KaBinding,
    context: KtElement,
    direct: Boolean,
  ): Boolean {
    val module = KaModuleProvider.getModule(project, context, useSiteModule = null)
    return budget.allowExpansion(binding, module, direct)
  }

  fun mayExpandSourceFactory(binding: KaBinding.AssistedFactory, context: KtElement): Boolean {
    val module = KaModuleProvider.getModule(project, context, useSiteModule = null)
    val allowed = budget.allowExpansion(binding, module, direct = false)
    if (allowed) boundaries.remove(SourceFactoryRequestId(binding.typeKey, module))
    return allowed
  }

  fun isConcrete(key: KaTypeKey): Boolean = budget.isConcrete(key.type)

  fun snapshot(): SourceFactoryResolution {
    val sites =
      linkedMapOf<
        ClassBindingIdentity,
        Map<KaModule, SmartPsiElementPointer<out KtElement>>,
      >()
    for ((identity, modules) in useSites) {
      ProgressManager.checkCanceled()
      sites[identity] = Collections.unmodifiableMap(LinkedHashMap(modules))
    }
    ProgressManager.checkCanceled()
    return SourceFactoryResolution(
      addedBindings.toList(),
      Collections.unmodifiableMap(sites),
      processed.toSet(),
      resolved.toSet(),
      boundaries.values.toList(),
      budget.snapshot(),
    )
  }

  private fun expansionSince(bindingStart: Int, requestStart: Int): SourceFactoryExpansion =
    SourceFactoryExpansion(
      addedBindings.subList(bindingStart, addedBindings.size).toList(),
      libraryRequests.subList(requestStart, libraryRequests.size).toList(),
    )

  private fun enqueue(
    key: KaTypeKey,
    pointer: SmartPsiElementPointer<out KtElement>,
    direct: Boolean,
  ): SourceFactoryRequestId? {
    if (key.type.classId !in sourceFactoryClassIds) return null
    if (!budget.isConcrete(key.type)) return null
    val context = pointer.element ?: return null
    val module = KaModuleProvider.getModule(project, context, useSiteModule = null)
    if (direct) budget.includeWrittenKey(key, isClass = true)
    val request = SourceFactoryRequest(key, module, pointer, direct)
    queue += request
    return request.id
  }

  private fun drain() {
    while (queue.isNotEmpty()) {
      ProgressManager.checkCanceled()
      val request = queue.removeFirst()
      if (!processed.add(request.id)) continue
      val context = request.context.element ?: continue
      val binding = resolveFactory(request, context)
      if (binding == null) {
        // The same class ID can name source in one module and a binary in another. Preserve the
        // request's original module when handing that unresolved candidate to library lookup.
        libraryRequests += request
        continue
      }
      resolved += request.id
      val identity = binding.classBindingIdentity() ?: continue
      useSites.getOrPut(identity) { linkedMapOf() }.putIfAbsent(request.module, request.context)
      if (!budget.allowExpansion(binding, request.module, request.direct)) {
        val boundary = SourceFactoryRequest(binding.typeKey, request.module, request.context)
        boundaries[boundary.id] = boundary
        continue
      }
      boundaries.remove(request.id)
      boundaries.remove(SourceFactoryRequestId(binding.typeKey, request.module))
      for (dependency in binding.dependencies) {
        ProgressManager.checkCanceled()
        val key = dependency.typeKey
        if (key.type.classId == null || !budget.isConcrete(key.type)) continue
        if (key.type.classId in sourceFactoryClassIds) {
          queue += SourceFactoryRequest(key, request.module, request.context)
        } else {
          libraryRequests += SourceFactoryRequest(key, request.module, request.context)
        }
      }
    }
  }

  private fun resolveFactory(
    request: SourceFactoryRequest,
    context: KtElement,
  ): KaBinding.AssistedFactory? {
    val classId = request.key.type.classId ?: return null
    return analyze(context) {
      val symbol = findClass(classId) as? KaNamedClassSymbol ?: return@analyze null
      val declaration = symbol.psi ?: return@analyze null
      val file = declaration.containingFile?.virtualFile ?: return@analyze null
      if (!fileIndex.isInContent(file)) return@analyze null
      if ((classId to file) !in sourceDeclarations) return@analyze null
      // Source metadata is interpreted with its owning module's configured annotations, never with
      // whichever module happened to request the shared project snapshot first.
      val options = declaration.metroIdeState().options
      val key = KaTypeKey(request.key.type, qualifierAnnotation(symbol, options))
      val identity = ClassBindingIdentity(key, symbol.classId, file)
      factories[identity]?.let {
        return@analyze it
      }
      val factoryType = restoreClassType(request.key.type) ?: return@analyze null
      val binding =
        assistedFactoryBinding(symbol, factoryType, options, pointerManager, key)
          ?: return@analyze null
      factories[identity] = binding
      addedBindings += binding
      binding
    }
  }
}
