// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.classLiteralClassId
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DeclarationResolutionScope
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.HintAvailability
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Cross-file passes that need the merged project shard: compiled contribution hints and
 * demand-driven library constructor-injection bindings.
 */
internal class LibraryIndexPostProcessor(
  private val project: Project,
  private val options: MetroOptions,
  private val bindings: MutableList<KaBinding>,
  private val consumers: List<ConsumerEntry>,
  private val graphs: List<KaGraphDeclaration>,
  private val contributions: MutableList<ContributionEntry>,
  private val sourceFactoryUseSites: SourceAssistedFactoryUseSites,
  private val consumerGraphContexts: ConsumerGraphContexts,
) {
  private val pointerManager = SmartPointerManager.getInstance(project)
  private val processedLibraryContributionScopes = HashMap<KtClassOrObject, MutableSet<ClassId>>()

  fun postProcess() {
    scanLibraryContributionHints()
    resolveLibraryInjectBindings()
  }

  /**
   * Discovers contributions from compiled dependencies the way the compiler does for classpath
   * merging (`ContributionHintFirGenerator` / `ContributedInterfaceSupertypeGenerator`): scanning
   * top-level hint functions in the `metro.hints` package, named after the scope class, whose
   * single parameter type is the contributing class.
   */
  private fun scanLibraryContributionHints() {
    val scopeIds = buildSet {
      graphs.forEach { addAll(it.scopeKeys) }
      contributions.forEach { addAll(it.scopeKeys) }
    }
    if (scopeIds.isEmpty()) return
    val useSites = useSitesByModule()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val allScope = GlobalSearchScope.allScope(project)
    val hints = mutableListOf<LibraryHint>()
    for (scopeId in scopeIds) {
      ProgressManager.checkCanceled()
      val hintFqName = MetroHints.hintCallableId(scopeId).asSingleFqName().asString()
      for (hintFunction in KotlinTopLevelFunctionFqnNameIndex[hintFqName, project, allScope]) {
        ProgressManager.checkCanceled()
        val virtualFile = hintFunction.containingFile.virtualFile ?: continue
        // Project-source contributions are already covered by the annotation sweeps; hints only
        // exist as generated declarations in binaries.
        if (fileIndex.isInContent(virtualFile)) continue
        hints += LibraryHint(scopeId, hintFunction)
      }
    }
    if (hints.isEmpty()) return

    val visibleModulesByHint = visibleModulesByHint(hints, useSites)
    for (hint in hints) {
      ProgressManager.checkCanceled()
      val visibleModules = visibleModulesByHint.getValue(hint.function)
      if (visibleModules.isEmpty()) continue
      val hintAvailability = if (hint.isNonPublic) HintAvailability(visibleModules) else null
      val context = useSites.getValue(visibleModules.first())
      processLibraryHint(hint.function, hint.scopeId, context, hintAvailability)
    }
  }

  private fun useSitesByModule(): Map<KaModule, KtElement> {
    val result = linkedMapOf<KaModule, KtElement>()
    val fileIndex = ProjectFileIndex.getInstance(project)

    fun addUseSite(element: PsiElement?) {
      if (element !is KtElement) return
      val virtualFile = element.containingFile?.virtualFile ?: return
      if (!fileIndex.isInContent(virtualFile)) return
      val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
      result.putIfAbsent(module, element)
    }

    graphs.forEach { addUseSite(it.pointer.element) }
    contributions.forEach { addUseSite(it.pointer.element) }
    consumers.forEach { addUseSite(it.pointer.element) }
    return result
  }

  private fun processLibraryHint(
    hintFunction: KtNamedFunction,
    scopeId: ClassId,
    context: KtElement,
    hintAvailability: HintAvailability?,
  ) {
    analyze(context) {
      val symbol = hintFunction.symbol as? KaNamedFunctionSymbol ?: return@analyze
      val contributedType =
        symbol.valueParameters.singleOrNull()?.returnType?.fullyExpandedType ?: return@analyze
      val classSymbol = (contributedType as? KaClassType)?.symbol as? KaNamedClassSymbol
      val ktClass = classSymbol?.psi as? KtClassOrObject ?: return@analyze
      val processedScopes = processedLibraryContributionScopes.getOrPut(ktClass) { mutableSetOf() }
      if (!processedScopes.add(scopeId)) return@analyze

      // Contribution-provider containers carry @Origin pointing back at the real contributing
      // class; prefer it for presentation and as the contribution anchor.
      val originClassId =
        classSymbol.annotations
          .firstOrNull { it.classId in options.originAnnotations }
          ?.arguments
          ?.firstOrNull { it.name.asString() == "value" }
          ?.let { classLiteralClassId(it.expression) }
      val originPsi = originClassId?.let { findClass(it)?.psi as? KtClassOrObject }
      val contributionAnchor = originPsi ?: ktClass

      val contributedClassId = originClassId ?: ktClass.getClassId()
      contributions +=
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(contributionAnchor),
          setOf(scopeId),
          contributedClassId,
          hintAvailability,
        )
      val classReplaces =
        classSymbol.annotations
          .filter { it.classId in options.allContributesAnnotations }
          .flatMapToSet { classListArgument(it, "replaces") }
      val classBindings = ktClass.bindingData(this, options)
      val originBindings =
        if (originPsi != null && originPsi != ktClass) originPsi.bindingData(this, options)
        else emptyList()
      val originSymbol =
        if (originPsi != null && originPsi != ktClass) originPsi.symbol as? KaNamedClassSymbol
        else classSymbol
      val fallbackContributionRank =
        originSymbol
          ?.annotations
          ?.asSequence()
          ?.filter { it.classId in options.contributesBindingAnnotations }
          ?.filter { scopeId in annotationScopeKeys(it) }
          ?.mapNotNull { annotation ->
            val value =
              annotation.arguments
                .firstOrNull { it.name.asString() == "rank" }
                ?.let { (it.expression as? KaAnnotationValue.ConstantValue)?.value?.value }
            when (value) {
              is Long -> value
              is Int -> value.toLong()
              else -> null
            }
          }
          ?.singleOrNull()
      // Explicit generated @Binds members are authoritative when a binary origin has multiple
      // supertypes and its contribution annotation's bound-type argument cannot be recovered.
      // A single scope-matched rank still belongs to those aliases even without class BindingData.
      val rankedContributions =
        (classBindings + originBindings).filter { contribution ->
          (contribution.kind == BindingData.Kind.ALIAS ||
            contribution.kind == BindingData.Kind.PROVIDED) &&
            contribution.isClassContribution &&
            contribution.contributionRank != Long.MIN_VALUE
        }
      for (data in classBindings) {
        bindings +=
          data.toKaBinding(
            ptr(ktClass),
            originClassId = data.originClassId ?: contributedClassId,
            replaces = data.replaces + classReplaces,
            contributionScopes = data.contributionScopes.ifEmpty { setOf(scopeId) },
            hintAvailability = hintAvailability,
          )
      }
      // Generated members hold the machine-readable binding declarations that annotation
      // arguments in binaries can't carry, like binding<T>() type args. Contribution-provider
      // containers hold @Provides members directly, and contributed classes hold nested
      // MetroContribution interfaces with @Binds members.
      val memberHolders = listOf(ktClass) + ktClass.declarations.filterIsInstance<KtClassOrObject>()
      for (holder in memberHolders) {
        ProgressManager.checkCanceled()
        for (member in holder.declarations.filterIsInstance<KtCallableDeclaration>()) {
          for (data in member.bindingData(this, options)) {
            val matchingContribution = rankedContributions.firstOrNull { contribution ->
              contribution.key == data.key && scopeId in contribution.contributionScopes
            }
            val inheritedRank =
              when {
                matchingContribution != null -> matchingContribution.contributionRank
                fallbackContributionRank != null -> fallbackContributionRank
                else -> data.contributionRank
              }
            val isRankedClassContribution =
              matchingContribution != null || fallbackContributionRank != null
            bindings +=
              data.toKaBinding(
                ptr(member),
                originClassId = contributedClassId,
                implementationName =
                  data.implementationName ?: originClassId?.shortClassName?.asString(),
                replaces = classReplaces,
                contributionScopes = setOf(scopeId),
                contributionRank = inheritedRank,
                isClassContribution = isRankedClassContribution || data.isClassContribution,
                hintAvailability = hintAvailability,
              )
          }
        }
      }
    }
  }

  /**
   * Modules from which Kotlin considers each [LibraryHint] visible.
   *
   * Public hints need only one module whose classpath contains the declaration. Internal/private
   * hints retain their complete use-site visibility sets so friend and source-set rules remain
   * authoritative, but unrelated module/hint pairs never enter an Analysis API session.
   */
  @OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
  private fun visibleModulesByHint(
    hints: List<LibraryHint>,
    useSites: Map<KaModule, KtElement>,
  ): Map<KtNamedFunction, Set<KaModule>> {
    val result = hints.associateTo(linkedMapOf()) { it.function to linkedSetOf<KaModule>() }
    val pendingPublic = hints.filterTo(linkedSetOf()) { !it.isNonPublic }
    val nonPublic = hints.filter { it.isNonPublic }
    for ((module, useSite) in useSites) {
      ProgressManager.checkCanceled()
      val resolutionScope = KaResolutionScope.forModule(module)
      val publicIterator = pendingPublic.iterator()
      while (publicIterator.hasNext()) {
        ProgressManager.checkCanceled()
        val hint = publicIterator.next()
        if (!resolutionScope.contains(hint.function)) continue
        result.getValue(hint.function) += module
        publicIterator.remove()
      }

      val candidates = nonPublic.filter { resolutionScope.contains(it.function) }
      if (candidates.isEmpty()) continue
      analyze(useSite) {
        val checker =
          createUseSiteVisibilityChecker(
            useSiteFile = useSite.containingKtFile.symbol,
            receiverExpression = null,
            position = useSite,
          )
        for (hint in candidates) {
          ProgressManager.checkCanceled()
          val hintSymbol = hint.function.symbol as? KaNamedFunctionSymbol ?: continue
          if (checker.isVisible(hintSymbol)) {
            result.getValue(hint.function) += module
          }
        }
      }
    }
    return result
  }

  /**
   * Demand-driven resolution of injected classes and assisted factories from compiled dependencies.
   * Source consumer sites and source/hint binding dependencies seed the same transitive traversal,
   * so generated providers also discover library dependencies without their own source consumers.
   */
  @OptIn(KaPlatformInterface::class)
  private fun resolveLibraryInjectBindings() {
    val queue = ArrayDeque<LibraryInjectRequest>()
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      val classId = consumer.typeClassId ?: continue
      if (consumer.multibindingId != null) {
        continue
      }
      val containerOwners = consumerGraphContexts.includedContainerPointers(consumer)
      if (containerOwners == null) {
        val context = consumerGraphContexts.pointer(consumer).element ?: continue
        queue += LibraryInjectRequest(consumer.key, classId, context)
      } else {
        for (owner in containerOwners) {
          val context = owner.element ?: continue
          queue += LibraryInjectRequest(consumer.key, classId, context)
        }
      }
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
      val resolved =
        analyze(request.context) {
          val classSymbol = findClass(request.classId) as? KaNamedClassSymbol ?: return@analyze null
          val psi = classSymbol.psi ?: return@analyze null
          // Project sources were already swept; finding nothing there was authoritative
          val virtualFile = psi.containingFile?.virtualFile ?: return@analyze null
          if (fileIndex.isInContent(virtualFile)) return@analyze null

          val actualQualifier = qualifierAnnotation(classSymbol, options)
          val isAssistedFactory = classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)
          // Keep a factory under its actual key even when the request has the wrong qualifier:
          // lazy-factory validation still needs its declaration, but normal lookup must not match.
          if (actualQualifier != request.key.qualifier && !isAssistedFactory) {
            return@analyze null
          }
          val factoryKey =
            if (actualQualifier == request.key.qualifier) request.key
            else KaTypeKey(request.key.type, actualQualifier)
          val defaultType = classSymbol.defaultType as? KaClassType ?: return@analyze null
          val requestedType =
            if (request.key.type.typeArguments.isEmpty()) defaultType
            else restoreClassType(request.key.type) ?: defaultType
          if (isAssistedFactory) {
            val factoryFunction = assistedFactoryFunction(requestedType)
            val samFunction = factoryFunction?.symbol as? KaNamedFunctionSymbol
            val targetType = factoryFunction?.returnType?.fullyExpandedType as? KaClassType
            val targetSymbol = targetType?.symbol as? KaNamedClassSymbol
            return@analyze ResolvedLibraryBinding(
              LibraryInjectBindingId(factoryKey, virtualFile),
              KaBinding.AssistedFactory(
                pointerManager.createSmartPsiElementPointer(psi),
                factoryKey,
                scopeAnnotation(classSymbol, options),
                targetSymbol?.classId?.shortClassName?.asString(),
                factoryFunction?.returnType?.let { typeKey(it, qualifier = null) },
                originClassId = classSymbol.classId,
                targetConstructorDependencies =
                  targetType?.let { injectConstructorDependencyKeys(it, options) }.orEmpty(),
                targetMemberDependencies =
                  targetType?.let { memberInjectDependencyKeys(it, options) }.orEmpty(),
                memberInjectionOwnerIds =
                  targetSymbol?.let { memberInjectOwnerClassIds(it) }.orEmpty(),
                factoryFunctionName = samFunction?.name?.asString(),
                factoryFunctionIsSuspend = samFunction?.isSuspend == true,
              ),
            )
          }
          if (classSymbol.classKind != KaClassKind.CLASS) return@analyze null

          val constructors = classSymbol.memberScope.constructors.toList()
          val hasInject =
            classSymbol.hasAnyAnnotation(options.injectAnnotations) ||
              constructors.any { it.hasAnyAnnotation(options.injectAnnotations) }
          val isAssisted =
            classSymbol.hasAnyAnnotation(options.assistedInjectAnnotations) ||
              constructors.any { it.hasAnyAnnotation(options.assistedInjectAnnotations) }
          if (!hasInject && !isAssisted) return@analyze null
          val constructorDependencies = injectConstructorDependencyKeys(requestedType, options)
          val memberDependencies = memberInjectDependencyKeys(requestedType, options)
          ResolvedLibraryBinding(
            LibraryInjectBindingId(request.key, virtualFile),
            KaBinding.ConstructorInjected(
              pointerManager.createSmartPsiElementPointer(psi),
              request.key,
              scopeAnnotation(classSymbol, options),
              classSymbol.name.asString(),
              originClassId = classSymbol.classId,
              constructorDependencies = constructorDependencies,
              memberDependencies = memberDependencies,
              memberInjectionOwnerIds = memberInjectOwnerClassIds(classSymbol),
              isAssisted = isAssisted,
            ),
          )
        }
      if (resolved == null) continue
      if (bindingIds.add(resolved.id)) bindings += resolved.binding
      for (dependency in resolved.binding.dependencies) {
        val key = dependency.typeKey
        val classId = key.type.classId ?: continue
        queue += LibraryInjectRequest(key, classId, request.context)
      }
    }
  }

  /** Rebuilds a request's concrete type inside its current, short-lived analysis session. */
  private fun KaSession.restoreClassType(snapshot: KaTypeSnapshot): KaClassType? {
    val classId = snapshot.classId ?: return null
    val typeArguments = ArrayList<KaClassType>(snapshot.typeArguments.size)
    for (typeArgument in snapshot.typeArguments) {
      val restoredArgument = restoreClassType(typeArgument) ?: return null
      typeArguments += restoredArgument
    }
    return buildClassType(classId) {
      isMarkedNullable = snapshot.renderedType.endsWith('?')
      for (typeArgument in typeArguments) argument(typeArgument)
    }
      as? KaClassType
  }

  /**
   * Hint-created providers have no source consumer entry, so their dependencies seed lookup too.
   */
  @OptIn(KaPlatformInterface::class)
  private fun enqueueBindingDependencies(queue: ArrayDeque<LibraryInjectRequest>) {
    val fileIndex = ProjectFileIndex.getInstance(project)
    val useSites = useSitesByModule()
    val seededFactoryUseSites =
      if (sourceFactoryUseSites.isEmpty()) null
      else {
        Collections.newSetFromMap(
          IdentityHashMap<Map<KaModule, SmartPsiElementPointer<out KtElement>>, Boolean>()
        )
      }
    val scopes = HashMap<KaModule, DeclarationResolutionScope>()
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      if (binding.dependencies.isEmpty()) continue
      val declaration = binding.pointer.element ?: continue
      val virtualFile = binding.pointer.virtualFile ?: continue
      if (fileIndex.isInContent(virtualFile)) {
        // Ordinary source providers/injectables already contributed their parameter consumers.
        // Generated class providers and assisted factories can own dependencies without such a
        // matching source declaration, so only those need an extra source seed.
        val needsSourceSeed =
          binding is KaBinding.AssistedFactory ||
            binding is KaBinding.Provided && binding.isClassContribution
        if (!needsSourceSeed) continue
        if (binding is KaBinding.AssistedFactory) {
          val requestingModules = sourceFactoryUseSites[binding]
          if (requestingModules != null && seededFactoryUseSites?.add(requestingModules) == false) {
            continue
          }
          if (!requestingModules.isNullOrEmpty()) {
            for (pointer in requestingModules.values) {
              val context = pointer.element ?: continue
              enqueueDependencies(binding, context, queue)
            }
            continue
          }
        }
        val context = declaration as? KtElement ?: continue
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
  )

  private data class LibraryInjectRequestId(val key: KaTypeKey, val module: KaModule)

  private data class LibraryInjectBindingId(val key: KaTypeKey, val file: VirtualFile)

  private data class ResolvedLibraryBinding(
    val id: LibraryInjectBindingId,
    val binding: KaBinding,
  )

  private class LibraryHint(val scopeId: ClassId, val function: KtNamedFunction) {
    val isNonPublic: Boolean =
      function.hasModifier(KtTokens.INTERNAL_KEYWORD) ||
        function.hasModifier(KtTokens.PRIVATE_KEYWORD)
  }
}

/** Source generic factories resolve dependencies from the modules that request their exact type. */
@OptIn(KaPlatformInterface::class)
internal fun sourceAssistedFactoryUseSites(
  project: Project,
  bindings: List<KaBinding>,
  consumers: List<ConsumerEntry>,
  graphs: List<KaGraphDeclaration>,
  graphContexts: ConsumerGraphContexts = ConsumerGraphContexts(graphs),
): SourceAssistedFactoryUseSites {
  val fileIndex = ProjectFileIndex.getInstance(project)
  var factoryGroups: MutableMap<SourceAssistedFactoryIdentity, SourceAssistedFactoryGroup>? = null
  var groupsByKey: MutableMap<KaTypeKey, MutableList<SourceAssistedFactoryGroup>>? = null
  for (binding in bindings) {
    ProgressManager.checkCanceled()
    if (binding !is KaBinding.AssistedFactory || binding.dependencies.isEmpty()) continue
    val virtualFile = binding.pointer.virtualFile ?: continue
    if (!fileIndex.isInContent(virtualFile)) continue
    val identity =
      SourceAssistedFactoryIdentity(binding.typeKey, binding.originClassId, virtualFile)
    val groups =
      factoryGroups
        ?: HashMap<SourceAssistedFactoryIdentity, SourceAssistedFactoryGroup>().also {
          factoryGroups = it
        }
    if (identity in groups) continue
    val declaration = binding.pointer.element ?: continue
    val group = SourceAssistedFactoryGroup(declaration)
    groups[identity] = group
    val keyedGroups =
      groupsByKey
        ?: HashMap<KaTypeKey, MutableList<SourceAssistedFactoryGroup>>().also {
          groupsByKey = it
        }
    keyedGroups.getOrPut(binding.typeKey) { mutableListOf() }.add(group)
  }
  val activeGroups = factoryGroups ?: return SourceAssistedFactoryUseSites.EMPTY
  val activeGroupsByKey = groupsByKey ?: return SourceAssistedFactoryUseSites.EMPTY

  val scopes = HashMap<KaModule, DeclarationResolutionScope>()
  for (consumer in consumers) {
    ProgressManager.checkCanceled()
    val groups = activeGroupsByKey[consumer.key] ?: continue
    val containerOwners = graphContexts.includedContainerPointers(consumer)
    if (containerOwners == null) {
      registerSourceFactoryUseSite(project, graphContexts.pointer(consumer), groups, scopes)
    } else {
      for (owner in containerOwners) {
        registerSourceFactoryUseSite(project, owner, groups, scopes)
      }
    }
  }

  val result =
    HashMap<SourceAssistedFactoryIdentity, Map<KaModule, SmartPsiElementPointer<out KtElement>>>()
  for ((identity, group) in activeGroups) {
    ProgressManager.checkCanceled()
    result[identity] = Collections.unmodifiableMap(LinkedHashMap(group.useSites))
  }
  return SourceAssistedFactoryUseSites(Collections.unmodifiableMap(result))
}

@OptIn(KaPlatformInterface::class)
private fun registerSourceFactoryUseSite(
  project: Project,
  pointer: SmartPsiElementPointer<out KtElement>,
  groups: List<SourceAssistedFactoryGroup>,
  scopes: MutableMap<KaModule, DeclarationResolutionScope>,
) {
  val context = pointer.element ?: return
  val module = KaModuleProvider.getModule(project, context, useSiteModule = null)
  for (group in groups) {
    ProgressManager.checkCanceled()
    if (!group.checkedModules.add(module)) continue
    val resolutionScope =
      scopes.getOrPut(module) {
        val platformScope = KaResolutionScope.forModule(module)
        DeclarationResolutionScope(platformScope::contains)
      }
    if (!resolutionScope.contains(group.declaration)) continue
    group.useSites[module] = pointer
  }
}

/** Inherited callables resolve from their exact owning graph, not the upstream declaration file. */
@OptIn(KaPlatformInterface::class)
internal class ConsumerGraphContexts(private val graphs: List<KaGraphDeclaration>) {
  private val pointersByGraphId:
    Map<GraphDeclarationId, SmartPsiElementPointer<out KtElement>> by lazy {
    graphs.associate { it.declarationId to it.pointer }
  }
  private val pointersByIncludedContainer:
    Map<KaTypeKey, List<SmartPsiElementPointer<out KtElement>>> by lazy {
    val pointers = linkedMapOf<KaTypeKey, MutableList<SmartPsiElementPointer<out KtElement>>>()
    val modulesByContainer = HashMap<KaTypeKey, MutableSet<KaModule>>()
    for (graph in graphs) {
      ProgressManager.checkCanceled()
      if (graph.includedBindingContainers.isEmpty()) continue
      val declaration = graph.pointer.element ?: continue
      val module =
        KaModuleProvider.getModule(declaration.project, declaration, useSiteModule = null)
      for (container in graph.includedBindingContainers) {
        val modules = modulesByContainer.getOrPut(container) { mutableSetOf() }
        if (!modules.add(module)) continue
        pointers.getOrPut(container) { mutableListOf() }.add(graph.pointer)
      }
    }
    pointers
  }

  fun pointer(consumer: ConsumerEntry): SmartPsiElementPointer<out KtElement> {
    val graphId = consumer.graphId ?: return consumer.pointer
    return pointersByGraphId[graphId] ?: consumer.pointer
  }

  /** Factory-input shards are shared, so every graph including their exact key can own a site. */
  fun includedContainerPointers(
    consumer: ConsumerEntry
  ): List<SmartPsiElementPointer<out KtElement>>? {
    if (consumer.graphId != null) return null
    val containerKey = consumer.includedContainerKey ?: return null
    return pointersByIncludedContainer[containerKey]
  }
}

/** Session-free source factory groups that remain reusable when equivalent shards are rebuilt. */
internal class SourceAssistedFactoryUseSites(
  private val groups:
    Map<SourceAssistedFactoryIdentity, Map<KaModule, SmartPsiElementPointer<out KtElement>>>
) {
  operator fun get(
    binding: KaBinding.AssistedFactory
  ): Map<KaModule, SmartPsiElementPointer<out KtElement>>? {
    val virtualFile = binding.pointer.virtualFile ?: return null
    return groups[
      SourceAssistedFactoryIdentity(binding.typeKey, binding.originClassId, virtualFile)]
  }

  fun isEmpty(): Boolean = groups.isEmpty()

  companion object {
    val EMPTY = SourceAssistedFactoryUseSites(emptyMap())
  }
}

internal data class SourceAssistedFactoryIdentity(
  val key: KaTypeKey,
  val originClassId: ClassId?,
  val virtualFile: VirtualFile,
)

private class SourceAssistedFactoryGroup(val declaration: PsiElement) {
  val checkedModules = HashSet<KaModule>()
  val useSites = linkedMapOf<KaModule, SmartPsiElementPointer<out KtElement>>()
}
