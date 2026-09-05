// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.ClassBindingIdentity
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.IdeTraceStageToken
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkItem
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkSummary
import dev.zacsweers.metro.idea.tracing.ideTraceFilePath
import dev.zacsweers.metro.idea.tracing.measure
import dev.zacsweers.metro.idea.tracing.measureRead
import dev.zacsweers.metro.idea.tracing.stage
import java.util.Collections
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/** A concrete request retains its compilation module without retaining an analysis session. */
internal data class SourceClassRequestId(val key: KaTypeKey, val module: KaModule)

internal class SourceClassRequest(
  val key: KaTypeKey,
  val module: KaModule,
  val context: SmartPsiElementPointer<out KtElement>,
  val direct: Boolean = false,
) {
  val id: SourceClassRequestId = SourceClassRequestId(key, module)
}

/** Immutable state that a binary-resolution pass can continue without repeating source work. */
internal class SourceClassResolution(
  val addedBindings: List<KaBinding>,
  val useSites: Map<ClassBindingIdentity, Map<KaModule, SmartPsiElementPointer<out KtElement>>>,
  val processedRequests: Set<SourceClassRequestId>,
  val resolvedRequests: Set<SourceClassRequestId>,
  val boundaryRequests: List<SourceClassRequest>,
  val budget: ClassBindingExpansionBudgetState,
  val dependencies: SourceClassDependencies,
) {
  val classUseSites: SourceClassUseSites = SourceClassUseSites(useSites)
  val incompleteBindings: Map<KaModule, Map<ClassBindingIdentity, String>>
    get() = budget.incompleteBindings

  /** Reuses completed resolution state after the caller validates its temporary read anchors. */
  fun withoutReadContexts(): SourceClassResolution {
    val retained = dependencies.withoutReadContexts()
    if (retained === dependencies) {
      return this
    }
    return SourceClassResolution(
      addedBindings,
      useSites,
      processedRequests,
      resolvedRequests,
      boundaryRequests,
      budget,
      retained,
    )
  }
}

internal class SourceClassExpansion(
  val addedBindings: List<KaBinding>,
  val libraryRequests: List<SourceClassRequest>,
  val handled: Boolean = true,
)

/**
 * Follows source class requests after file shards have been merged. Each concrete binding is
 * accepted once per declaration, while each requesting module keeps its own dependency traversal.
 * Binary lookup continues the same state when a dependency leads back to source.
 */
@OptIn(KaPlatformInterface::class)
internal class SourceClassBindingPostProcessor(
  private val project: Project,
  bindings: List<KaBinding>,
  private val consumers: List<ConsumerEntry>,
  private val consumerOwnership: ConsumerOwnershipBundle,
  previous: SourceClassResolution? = null,
) {
  private val pointerManager = SmartPointerManager.getInstance(project)
  private val fileIndex = ProjectFileIndex.getInstance(project)
  private val classBindings = linkedMapOf<ClassBindingIdentity, KaBinding>()
  private val sourceClassIds = hashSetOf<ClassId>()
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
  private val boundaries = linkedMapOf<SourceClassRequestId, SourceClassRequest>()
  private val queue = ArrayDeque<SourceClassRequest>()
  private val libraryRequests = mutableListOf<SourceClassRequest>()
  private val dependencies =
    SourceClassDependencies.Builder(
      pointerManager,
      previous?.dependencies ?: SourceClassDependencies.EMPTY,
    )
  private val budget: ClassBindingExpansionBudget

  init {
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      if (binding !is KaBinding.AssistedFactory && binding !is KaBinding.ConstructorInjected) {
        continue
      }
      val identity = binding.classBindingIdentity() ?: continue
      if (!fileIndex.isInContent(identity.virtualFile)) {
        continue
      }
      classBindings.putIfAbsent(identity, binding)
      identity.originClassId?.let {
        sourceClassIds += it
        sourceDeclarations += it to identity.virtualFile
      }
    }
    for (binding in addedBindings) {
      ProgressManager.checkCanceled()
      val identity = binding.classBindingIdentity() ?: continue
      classBindings.putIfAbsent(identity, binding)
      identity.originClassId?.let {
        sourceClassIds += it
        sourceDeclarations += it to identity.virtualFile
      }
    }
    previous?.useSites?.forEach { (identity, sites) -> useSites[identity] = LinkedHashMap(sites) }
    previous?.boundaryRequests?.forEach { boundaries[it.id] = it }
    budget =
      ClassBindingExpansionBudget(classBindings.keys, sourceDeclarations.size, previous?.budget)
    // Derived bindings from a previous pass are not newly written inputs. Explicit hint bindings
    // are, and can raise the boundary when binary resolution resumes a source-only snapshot.
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      val writtenKey = binding.writtenClassBudgetKey() ?: continue
      budget.includeWrittenKey(writtenKey, writtenKey.type.classId in sourceClassIds)
    }
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      budget.includeWrittenKey(consumer.key, consumer.typeClassId in sourceClassIds)
    }
  }

  /** Captures PSI seeds once and accepts each completed lookup in deterministic request order. */
  suspend fun resolveInitial(
    executor: SnapshotReadExecutor,
    trace: IdeTraceOperation? = null,
  ): SourceClassResolution {
    val seeds = executor.read { captureInitialRequests() }
    seeds.forEach(::enqueue)
    val work = trace?.let { IdeTraceWorkSummary(it, "source.class") }
    try {
      drain(executor, work)
      return snapshot()
    } finally {
      report(trace, work)
    }
  }

  /** Synchronous callers already own read access and use the same lookup and acceptance steps. */
  fun resolveInitial(trace: IdeTraceOperation? = null): SourceClassResolution {
    captureInitialRequests().forEach(::enqueue)
    val work = trace?.let { IdeTraceWorkSummary(it, "source.class") }
    try {
      drain(work)
      return snapshot()
    } finally {
      report(trace, work)
    }
  }

  private fun captureInitialRequests(): List<CapturedRequest> = buildList {
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      if (consumer.multibindingId != null) {
        continue
      }
      val owners = consumerOwnership.owningGraphPointers(consumer)
      if (owners == null) {
        add(
          captureRequest(consumer.key, consumerOwnership.pointer(consumer), true, consumer.pointer)
        )
      } else {
        for (owner in owners) {
          add(captureRequest(consumer.key, owner, true, consumer.pointer))
        }
      }
    }
    for (binding in bindingOnlySeeds) {
      ProgressManager.checkCanceled()
      val declaration = binding.pointer.element as? KtElement ?: continue
      val pointer = pointerManager.createSmartPsiElementPointer(declaration)
      for (dependency in binding.dependencies) {
        add(captureRequest(dependency.typeKey, pointer, true))
      }
    }
  }

  private fun report(trace: IdeTraceOperation?, work: IdeTraceWorkSummary?) {
    trace?.attribute("requests.processed", processed.size)
    trace?.attribute("requests.resolved", resolved.size)
    trace?.attribute("requests.library", libraryRequests.size)
    trace?.attribute("requests.boundary", boundaries.size)
    trace?.attribute("bindings.added", addedBindings.size)
    work?.report()
  }

  /** Retry only previous boundaries; already-expanded source keys remain memoized. */
  suspend fun resumeBoundaries(executor: SnapshotReadExecutor): SourceClassExpansion {
    val bindingStart = addedBindings.size
    val requestStart = libraryRequests.size
    enqueueBoundaries()
    drain(executor)
    return expansionSince(bindingStart, requestStart)
  }

  private fun enqueueBoundaries() {
    for (request in boundaries.values.toList()) {
      processed.remove(request.id)
      queue += request
    }
  }

  /** Binary acceptance calls this sequentially so both queues retain their shared budget order. */
  suspend fun resolveFromBinary(
    request: SourceClassRequest,
    executor: SnapshotReadExecutor,
  ): SourceClassExpansion {
    val bindingStart = addedBindings.size
    val requestStart = libraryRequests.size
    val capture = executor.read {
      captureRequest(request.key, request.context, request.direct, module = request.module)
    }
    val id = enqueue(capture)
    drain(executor)
    return expansionSince(bindingStart, requestStart, id != null && id in resolved)
  }

  /**
   * The binary queue shares these limits so crossing the source/library boundary cannot evade them.
   */
  fun expandClassBinding(binding: KaBinding, module: KaModule, direct: Boolean): Boolean =
    budget.allowExpansion(binding, module, direct)

  fun mayExpandSourceBinding(binding: KaBinding, module: KaModule): Boolean {
    val allowed = budget.allowExpansion(binding, module, direct = false)
    if (allowed) {
      boundaries.remove(SourceClassRequestId(binding.typeKey, module))
    }
    return allowed
  }

  fun isConcrete(key: KaTypeKey): Boolean = budget.isConcrete(key.type)

  /** Adds detached binary inputs after their ordered acceptance into the shared snapshot. */
  fun includeDependencies(captured: SourceClassDependencies) {
    dependencies.include(captured)
  }

  fun snapshot(): SourceClassResolution {
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
    return SourceClassResolution(
      addedBindings.toList(),
      Collections.unmodifiableMap(sites),
      processed.toSet(),
      resolved.toSet(),
      boundaries.values.toList(),
      budget.snapshot(),
      dependencies.build(),
    )
  }

  private fun expansionSince(
    bindingStart: Int,
    requestStart: Int,
    handled: Boolean = true,
  ): SourceClassExpansion =
    SourceClassExpansion(
      addedBindings.subList(bindingStart, addedBindings.size).toList(),
      libraryRequests.subList(requestStart, libraryRequests.size).toList(),
      handled,
    )

  /** A canceled seed capture cannot leave requests or dependency stamps in coordinator state. */
  private class CapturedRequest(
    val request: SourceClassRequest?,
    val dependencies: SourceClassDependencies,
  )

  private fun captureRequest(
    key: KaTypeKey,
    pointer: SmartPsiElementPointer<out KtElement>,
    direct: Boolean,
    source: SmartPsiElementPointer<out PsiElement> = pointer,
    module: KaModule? = null,
  ): CapturedRequest {
    val capturedDependencies = SourceClassDependencies.Builder(pointerManager)
    val context = pointer.element ?: return CapturedRequest(null, capturedDependencies.build())
    context.containingFile?.let(capturedDependencies::recordContext)
    val hasErrors = capturedDependencies.recordErrorTypes(key.type, context, source)
    val request =
      if (hasErrors || key.type.classId == null) {
        null
      } else {
        SourceClassRequest(
          key,
          module ?: KaModuleProvider.getModule(project, context, useSiteModule = null),
          pointer,
          direct,
        )
      }
    return CapturedRequest(request, capturedDependencies.build())
  }

  private fun enqueue(capture: CapturedRequest): SourceClassRequestId? {
    dependencies.include(capture.dependencies)
    val request = capture.request ?: return null
    if (!budget.isConcrete(request.key.type)) {
      return null
    }
    if (request.direct) {
      budget.includeWrittenKey(request.key, isClass = true)
    }
    queue += request
    return request.id
  }

  /**
   * Each batch borrows the coordinator's lookup cache while workers run. Acceptance starts after
   * all reads join, so workers only observe previously accepted bindings. Dependencies enter the
   * tail of the queue in the same order as sequential discovery.
   */
  private suspend fun drain(executor: SnapshotReadExecutor, work: IdeTraceWorkSummary? = null) {
    while (queue.isNotEmpty()) {
      val requests = takeRequests(executor.parallelism)
      val reads = executor.map(requests, ::describe) { request -> captureRead(request, work) }
      for ((request, read) in requests.zip(reads)) {
        ProgressManager.checkCanceled()
        // Two modules can find the same declaration in one batch. The first accepted binding
        // owns its dependency shape. Capture later use sites against that canonical cache entry.
        val existing = read?.cacheIdentity?.let(classBindings::get)
        val canonicalRead =
          if (existing != null && existing !== read.binding) {
            executor.read { captureRead(request, work) }
          } else {
            read
          }
        acceptRead(request, canonicalRead)
      }
    }
  }

  private fun drain(work: IdeTraceWorkSummary? = null) {
    while (queue.isNotEmpty()) {
      val request = takeRequests(1).singleOrNull() ?: continue
      acceptRead(request, captureRead(request, work))
    }
  }

  private fun takeRequests(limit: Int): List<SourceClassRequest> = buildList {
    while (queue.isNotEmpty() && size < limit) {
      ProgressManager.checkCanceled()
      val request = queue.removeFirst()
      if (processed.add(request.id)) {
        add(request)
      }
    }
  }

  /** Display data is resolved inside the worker's read action, alongside the class lookup. */
  @OptIn(KaExperimentalApi::class)
  private fun describe(request: SourceClassRequest): IndexBuildFile {
    val className = request.key.type.classId?.asFqNameString() ?: "<unknown>"
    return IndexBuildFile(
      className,
      request.context.virtualFile?.presentableUrl ?: className,
      request.module.moduleDescription,
    )
  }

  private class ClassRead(
    val binding: KaBinding?,
    val cacheIdentity: ClassBindingIdentity?,
    val dependencyKeys: List<KaTypeKey>,
    val dependencies: SourceClassDependencies,
  )

  /**
   * Analysis symbols and PSI reads stay inside one retryable lookup; only detached data escapes.
   */
  @OptIn(KaExperimentalApi::class)
  private fun captureRead(request: SourceClassRequest, work: IdeTraceWorkSummary?): ClassRead? {
    val context = request.context.element ?: return null
    val capturedDependencies = SourceClassDependencies.Builder(pointerManager)
    context.containingFile?.let(capturedDependencies::recordContext)
    return work.measure { item ->
      if (item != null) {
        item.module = request.module.moduleDescription
        item.className = request.key.type.classId?.asFqNameString() ?: "<unknown>"
        item.file = context.containingFile?.virtualFile?.let { ideTraceFilePath(project, it) }
      }
      item.measureRead {
        val resolvedClass = resolveClass(request, context, item, capturedDependencies)
        val binding = resolvedClass?.binding
        val keys =
          item.stage("source.class.dependencyExpansion") {
            buildList {
              if (binding == null) {
                return@buildList
              }
              for (dependency in binding.dependencies) {
                ProgressManager.checkCanceled()
                val key = dependency.typeKey
                if (capturedDependencies.recordErrorTypes(key.type, context, binding.pointer)) {
                  continue
                }
                if (key.type.classId != null) {
                  add(key)
                }
              }
            }
          }
        item?.outcome =
          if (binding == null) {
            "library"
          } else {
            "resolved"
          }
        ClassRead(binding, resolvedClass?.identity, keys, capturedDependencies.build())
      }
    }
  }

  /** All cache, budget, use-site, and queue mutations belong to the ordered collector. */
  private fun acceptRead(request: SourceClassRequest, read: ClassRead?) {
    if (read == null) {
      return
    }
    dependencies.include(read.dependencies)
    var binding = read.binding
    if (binding == null) {
      // The same class ID can name source in one module and a binary in another. Preserve the
      // request's original module when handing that unresolved candidate to library lookup.
      libraryRequests += request
      return
    }
    val cacheIdentity = checkNotNull(read.cacheIdentity)
    val existing = classBindings.putIfAbsent(cacheIdentity, binding)
    if (existing == null) {
      addedBindings += binding
    } else {
      binding = existing
    }
    resolved += request.id
    val identity = binding.classBindingIdentity() ?: return
    useSites.getOrPut(identity) { linkedMapOf() }.putIfAbsent(request.module, request.context)
    if (!budget.allowExpansion(binding, request.module, request.direct)) {
      val boundary = SourceClassRequest(binding.typeKey, request.module, request.context)
      boundaries[boundary.id] = boundary
      return
    }
    boundaries.remove(request.id)
    boundaries.remove(SourceClassRequestId(binding.typeKey, request.module))
    for (key in read.dependencyKeys) {
      ProgressManager.checkCanceled()
      if (budget.isConcrete(key.type)) {
        queue += SourceClassRequest(key, request.module, request.context)
      }
    }
  }

  private class ResolvedClass(val binding: KaBinding, val identity: ClassBindingIdentity)

  /**
   * Analysis entry and exit measure session overhead around the separately measured body stages.
   */
  private fun resolveClass(
    request: SourceClassRequest,
    context: KtElement,
    item: IdeTraceWorkItem?,
    capturedDependencies: SourceClassDependencies.Builder,
  ): ResolvedClass? {
    item?.cache = "miss"
    val classId = request.key.type.classId ?: return null
    val analysisEntry = item?.beginStage("source.class.analysisEntry")
    var analysisExit: IdeTraceStageToken? = null
    try {
      return analyze(context) {
        analysisEntry?.finish()
        try {
          val owner =
            item.stage("source.class.analysisSetup") { context.containingFile?.virtualFile }
          val symbol =
            item.stage("source.class.findClass") {
              val symbol = findClass(classId) as? KaNamedClassSymbol
              if (symbol == null) {
                capturedDependencies.recordUnresolved(classId, owner, request.module)
              }
              symbol
            } ?: return@analyze null
          val declaration: PsiElement
          val file: VirtualFile
          val onDeclarationFile: (PsiFile) -> Unit
          item.stage("source.class.declarationEligibility") {
            declaration = symbol.psi ?: return@analyze null
            file = declaration.containingFile?.virtualFile ?: return@analyze null
            if (!fileIndex.isInContent(file)) {
              return@analyze null
            }
            onDeclarationFile = {
              val dependencyFile = it.virtualFile
              if (dependencyFile != null && fileIndex.isInContent(dependencyFile)) {
                capturedDependencies.record(it, owner)
              }
            }
            declaration.containingFile?.let(onDeclarationFile)
            val isObject =
              symbol.classKind == KaClassKind.OBJECT ||
                symbol.classKind == KaClassKind.COMPANION_OBJECT
            if (!isObject && (classId to file) !in sourceDeclarations) {
              return@analyze null
            }
            if (request.key.type.isMarkedNullable) {
              return@analyze null
            }
          }
          val options: MetroOptions
          val key: KaTypeKey
          val identity: ClassBindingIdentity
          item.stage("source.class.optionsAndQualifierLookup") {
            // Source metadata uses the owning module's configured annotations. Shared project
            // snapshots may be requested from another module.
            options = declaration.metroIdeState().options
            key = KaTypeKey(request.key.type, qualifierAnnotation(symbol, options))
            identity = ClassBindingIdentity(key, symbol.classId, file)
          }
          item.stage("source.class.cacheCheck") {
            classBindings[identity]?.let {
              if (key != request.key && it !is KaBinding.AssistedFactory) {
                return@analyze null
              }
              item?.cache = "reused"
              return@analyze ResolvedClass(it, identity)
            }
          }
          item.stage("source.class.bindingConstruction") {
            val binding =
              resolveClassBinding(symbol, request.key, options, pointerManager, onDeclarationFile)
                ?: return@analyze null
            item?.cache = "computed"
            ResolvedClass(binding, identity)
          }
        } finally {
          analysisExit = item?.beginStage("source.class.analysisExit")
        }
      }
    } catch (failure: Throwable) {
      analysisEntry?.finish(failure)
      analysisExit?.finish(failure)
      throw failure
    } finally {
      analysisEntry?.finish()
      analysisExit?.finish()
    }
  }
}
