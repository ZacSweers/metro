// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.OptionalBindingBehavior
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.BindingKind
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/** Key plus display metadata for a consuming site. */
internal class ConsumedSite(
  val contextKey: KaContextualTypeKey,
  val isAbstractType: Boolean,
  /** For `Set`/`Map` aggregate sites, the multibinding id collecting contributed elements. */
  val multibindingId: String? = null,
  /** The consumed type's class, when it is a class type. */
  val typeClassId: ClassId? = null,
) {
  val key: KaTypeKey
    get() = contextKey.typeKey
}

/** Key plus display metadata for a single binding originated by a provider declaration. */
internal class BindingData(
  val key: KaTypeKey,
  val kind: BindingKind,
  val scope: KaAnnotationSnapshot?,
  val implementationName: String?,
  /** For `@Binds`-style bindings, the key of the source/impl binding this delegates to. */
  val consumedKey: KaTypeKey? = null,
  /** For multibinding contributions, the aggregate binding id. See [KaBinding]. */
  val multibindingId: String? = null,
  /** See [KaBinding.originClassId]. */
  val originClassId: ClassId? = null,
  /** See [KaBinding.replaces]. */
  val replaces: Set<ClassId> = emptySet(),
  /** See [KaBinding.contributionScopes]. */
  val contributionScopes: Set<ClassId> = emptySet(),
)

// ---------------------------------------------------------------------------------------------
// Index building
// ---------------------------------------------------------------------------------------------

/** The Metro declarations extracted from a single file, cached against that file's PSI. */
internal class FileShard(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphNode>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
) {
  companion object {
    val EMPTY =
      FileShard(
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
      )
  }
}

/**
 * Two-phase index construction: [buildShard] extracts one file's declarations (cached per file by
 * the resolution service), and [postProcess] runs the cross-file library passes over the merged
 * shard accumulators passed in via the constructor.
 */
internal class IndexBuilder(
  private val project: Project,
  private val options: MetroOptions,
  private val bindings: MutableList<KaBinding> = mutableListOf(),
  private val consumers: MutableList<ConsumerEntry> = mutableListOf(),
  private val graphs: MutableList<KaGraphNode> = mutableListOf(),
  private val contributions: MutableList<ContributionEntry> = mutableListOf(),
  private val assistedSites: MutableList<AssistedSite> = mutableListOf(),
  private val bindingContainerEntries: MutableList<BindingContainerEntry> = mutableListOf(),
) {
  private val pointerManager = SmartPointerManager.getInstance(project)

  private val processedBindingCallables = HashSet<KtDeclaration>()
  private val processedInjectClasses = HashSet<KtClassOrObject>()
  private val processedMemberInjects = HashSet<KtDeclaration>()
  private val processedContributions = HashSet<KtClassOrObject>()
  private val processedLibraryContributionScopes = HashMap<KtClassOrObject, MutableSet<ClassId>>()
  private val processedGraphs = HashSet<KtClassOrObject>()
  private val processedCircuitInjects = HashSet<KtDeclaration>()
  private val processedAssistedFactories = HashSet<KtClassOrObject>()
  private val processedContainers = HashSet<KtClassOrObject>()

  fun buildShard(file: KtFile): FileShard {
    val bindingCallableNames =
      shortNames(
        options.providesAnnotations +
          options.bindsAnnotations +
          options.multibindsAnnotations +
          bindsOptionalOfAnnotations(options)
      )
    val injectNames = shortNames(options.injectAnnotations + options.assistedInjectAnnotations)
    val contributesNames = shortNames(options.allContributesAnnotations)
    val graphNames =
      shortNames(options.dependencyGraphAnnotations + options.graphExtensionAnnotations)
    val assistedFactoryNames = shortNames(options.assistedFactoryAnnotations)
    val containerNames = shortNames(options.bindingContainerAnnotations)
    val circuitName = CircuitClassIds.CircuitInject.shortClassName.asString()

    for (entry in PsiTreeUtil.collectElementsOfType(file, KtAnnotationEntry::class.java)) {
      ProgressManager.checkCanceled()
      val shortName = entry.shortName?.asString() ?: continue
      val declaration = entry.getStrictParentOfType<KtDeclaration>() ?: continue
      if (shortName in bindingCallableNames) processBindingCallable(declaration)
      if (shortName in injectNames) processInjectAnnotated(declaration)
      if (shortName in contributesNames) processContribution(declaration)
      if (shortName in graphNames) processGraph(declaration)
      if (shortName in assistedFactoryNames) processAssistedFactory(declaration)
      if (shortName in containerNames) processBindingContainer(declaration)
      if (options.enableCircuitCodegen && shortName == circuitName) {
        processCircuitInject(declaration)
      }
    }
    return FileShard(
      bindings,
      consumers,
      graphs,
      contributions,
      assistedSites,
      bindingContainerEntries,
    )
  }

  private fun shortNames(classIds: Set<ClassId>): Set<String> {
    return classIds.mapTo(mutableSetOf()) { it.shortClassName.asString() }
  }

  /** Runs the cross-file library passes over previously merged shard accumulators. */
  fun postProcess() {
    // Seed dedup with classes already contributed from project sources
    contributions.mapNotNullTo(processedContributions) { it.pointer.element as? KtClassOrObject }
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
    // Analyze from a project-source use site: library-use-site sessions deserialize annotation
    // values differently (e.g. unresolved class literal ids).
    val context =
      graphs.firstNotNullOfOrNull { it.pointer.element }
        ?: contributions.firstNotNullOfOrNull { it.pointer.element }
        ?: return
    val useSiteModulesByScope = useSiteModulesByScope()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val allScope = GlobalSearchScope.allScope(project)
    for (scopeId in scopeIds) {
      ProgressManager.checkCanceled()
      val hintFqName = MetroHints.hintCallableId(scopeId).asSingleFqName().asString()
      for (hintFunction in KotlinTopLevelFunctionFqnNameIndex[hintFqName, project, allScope]) {
        val virtualFile = hintFunction.containingFile.virtualFile ?: continue
        // Project-source contributions are already covered by the annotation sweeps; hints only
        // exist as generated declarations in binaries.
        if (fileIndex.isInContent(virtualFile)) continue
        // Mirrors the compiler's visibility filtering: internal hints are visible only from their
        // own module and formal friend/associated compilations.
        if (
          hintFunction.hasModifier(KtTokens.INTERNAL_KEYWORD) &&
            !isVisibleInternalHint(hintFunction, useSiteModulesByScope[scopeId].orEmpty())
        ) {
          continue
        }
        processLibraryHint(hintFunction, scopeId, context)
      }
    }
  }

  private fun useSiteModulesByScope(): Map<ClassId, Set<KaModule>> {
    val result = mutableMapOf<ClassId, MutableSet<KaModule>>()

    fun addUseSite(element: PsiElement?, scopeKeys: Set<ClassId>) {
      if (element !is KtElement) return
      val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
      for (scopeKey in scopeKeys) {
        result.getOrPut(scopeKey, ::mutableSetOf) += module
      }
    }

    graphs.forEach { addUseSite(it.pointer.element, it.scopeKeys) }
    contributions.forEach { addUseSite(it.pointer.element, it.scopeKeys) }
    return result
  }

  private fun processLibraryHint(
    hintFunction: KtNamedFunction,
    scopeId: ClassId,
    context: KtElement,
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
        )
      val classReplaces =
        classSymbol.annotations
          .filter { it.classId in options.allContributesAnnotations }
          .flatMapTo(hashSetOf()) { classListArgument(it, "replaces") }
      for (data in bindingData(ktClass, options)) {
        bindings +=
          bindingFrom(
            ptr(ktClass),
            data,
            originClassId = data.originClassId ?: contributedClassId,
            replaces = data.replaces + classReplaces,
            contributionScopes = data.contributionScopes.ifEmpty { setOf(scopeId) },
          )
      }
      // Generated members hold the machine-readable binding declarations that annotation
      // arguments in binaries can't carry (e.g. binding<T>() type args): contribution-provider
      // containers hold @Provides members directly, and contributed classes hold nested
      // MetroContribution interfaces with @Binds members.
      val memberHolders = listOf(ktClass) + ktClass.declarations.filterIsInstance<KtClassOrObject>()
      for (holder in memberHolders) {
        for (member in holder.declarations.filterIsInstance<KtCallableDeclaration>()) {
          for (data in bindingData(member, options)) {
            bindings +=
              bindingFrom(
                ptr(member),
                data,
                originClassId = contributedClassId,
                implementationName =
                  data.implementationName ?: originClassId?.shortClassName?.asString(),
                replaces = classReplaces,
                contributionScopes = setOf(scopeId),
              )
          }
        }
      }
    }
  }

  /** Whether an internal [hintFunction] is visible from a formal friend/associated use site. */
  private fun isVisibleInternalHint(
    hintFunction: KtNamedFunction,
    useSiteModules: Set<KaModule>,
  ): Boolean {
    for (useSiteModule in useSiteModules) {
      val hintModule = KaModuleProvider.getModule(project, hintFunction, useSiteModule)
      if (hintModule == useSiteModule) return true
      if (useSiteModule is KaSourceModule && hintModule in useSiteModule.directFriendDependencies) {
        return true
      }
    }
    return false
  }

  /**
   * Demand-driven resolution of constructor-injected classes from compiled dependencies: the
   * annotation sweeps only cover project sources, but inject annotations survive in library
   * metadata. For each consumer key with no project-source binding, checks whether the consumed
   * class itself is injectable and synthesizes a binding entry targeting the library declaration.
   */
  private fun resolveLibraryInjectBindings() {
    val bindingKeys = bindings.mapTo(HashSet()) { it.key }
    val unresolved =
      consumers
        .filter {
          it.multibindingId == null &&
            it.typeClassId != null &&
            it.key.qualifier == null &&
            it.key !in bindingKeys
        }
        .groupBy { it.key }
    if (unresolved.isEmpty()) return

    val fileIndex = ProjectFileIndex.getInstance(project)
    for ((key, sites) in unresolved) {
      ProgressManager.checkCanceled()
      val context = sites.firstNotNullOfOrNull { it.pointer.element } ?: continue
      analyze(context) {
        val classSymbol =
          findClass(sites.first().typeClassId!!) as? KaNamedClassSymbol ?: return@analyze
        val psi = classSymbol.psi ?: return@analyze
        // Project sources were already swept; finding nothing there was authoritative
        val virtualFile = psi.containingFile?.virtualFile ?: return@analyze
        if (fileIndex.isInContent(virtualFile)) return@analyze
        if (classSymbol.classKind != KaClassKind.CLASS) return@analyze

        val constructors = classSymbol.memberScope.constructors.toList()
        val hasInject =
          classSymbol.hasAnyAnnotation(options.injectAnnotations) ||
            constructors.any { it.hasAnyAnnotation(options.injectAnnotations) }
        val isAssisted =
          classSymbol.hasAnyAnnotation(options.assistedInjectAnnotations) ||
            constructors.any { it.hasAnyAnnotation(options.assistedInjectAnnotations) }
        if (!hasInject || isAssisted) return@analyze

        bindings +=
          KaBinding(
            pointerManager.createSmartPsiElementPointer(psi),
            key,
            BindingKind.INJECT,
            scopeAnnotation(classSymbol, options),
            classSymbol.name.asString(),
          )
      }
    }
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  /**
   * Builds a [KaBinding] from a [BindingData], anchoring it at [pointer]. Callers override only the
   * fields that differ from the computed data (e.g. contribution-derived origin/scopes).
   */
  private fun bindingFrom(
    pointer: SmartPsiElementPointer<out PsiElement>,
    data: BindingData,
    containerId: ClassId? = null,
    originClassId: ClassId? = data.originClassId,
    implementationName: String? = data.implementationName,
    replaces: Set<ClassId> = data.replaces,
    contributionScopes: Set<ClassId> = data.contributionScopes,
  ): KaBinding {
    return KaBinding(
      pointer,
      data.key,
      data.kind,
      data.scope,
      implementationName,
      data.multibindingId,
      originClassId = originClassId,
      containerId = containerId,
      replaces = replaces,
      contributionScopes = contributionScopes,
    )
  }

  /**
   * The graph an instance binding (a `@Provides` factory parameter) belongs to: the factory's
   * enclosing graph when the factory is nested, otherwise the graph type the factory's creator
   * function returns (top-level `createGraphFactory`-style factories).
   */
  private fun KaSession.instanceBindingContainerId(parameter: KtParameter): ClassId? {
    val createFunction = parameter.ownerFunction as? KtNamedFunction ?: return null
    val factory = createFunction.containingClassOrObject
    factory?.containingClassOrObject?.getClassId()?.let {
      return it
    }
    val returnType =
      (createFunction.symbol as? KaNamedFunctionSymbol)?.returnType?.fullyExpandedType
    (returnType as? KaClassType)?.classId?.let {
      return it
    }
    return factory?.getClassId()
  }

  /** `@Provides`/`@Binds`/`@Multibinds` callables, including instance-binding factory params. */
  private fun processBindingCallable(declaration: KtDeclaration) {
    val target =
      when (declaration) {
        is KtPropertyAccessor -> declaration.property
        else -> declaration
      }
    when (target) {
      is KtNamedFunction,
      is KtProperty,
      is KtParameter -> {
        if (!processedBindingCallables.add(target)) return
        analyze(target) {
          val containerId =
            when (target) {
              is KtParameter -> instanceBindingContainerId(target)
              is KtCallableDeclaration -> target.containingClassOrObject?.getClassId()
              else -> null
            }
          val dataEntries = bindingData(target, options)
          val consumerOriginClassId = dataEntries.firstNotNullOfOrNull { it.originClassId }
          val consumerContributionScopes =
            dataEntries.flatMapTo(mutableSetOf()) { it.contributionScopes }
          for (data in dataEntries) {
            bindings += bindingFrom(ptr(target), data, containerId = containerId)
            // The @Binds source/impl side is itself a consumer of the impl binding.
            if (data.consumedKey != null) {
              val consumerAnchor =
                (target as? KtNamedFunction)?.valueParameters?.singleOrNull()
                  ?: (target as? KtCallableDeclaration)?.receiverTypeReference
                  ?: target
              consumers +=
                ConsumerEntry(
                  ptr(consumerAnchor),
                  data.consumedKey,
                  originClassId = consumerOriginClassId,
                  contributionScopes = consumerContributionScopes,
                  containerId = containerId,
                )
            }
          }
          // Provider function parameters are consumers themselves.
          if (target is KtNamedFunction && !target.hasAnyOfAnnotations(options.bindsAnnotations)) {
            for (parameter in target.valueParameters) {
              addParameterConsumer(
                parameter,
                originClassId = consumerOriginClassId,
                contributionScopes = consumerContributionScopes,
                containerId = containerId,
              )
            }
          }
        }
      }
      else -> {}
    }
  }

  /** `@Inject`/`@AssistedInject` on classes, constructors, and members. */
  private fun processInjectAnnotated(declaration: KtDeclaration) {
    when (declaration) {
      is KtConstructor<*> -> processInjectClass(declaration.getContainingClassOrObject())
      is KtClassOrObject -> processInjectClass(declaration)
      is KtProperty -> {
        // Member injection site
        if (declaration.isLocal || !processedMemberInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaCallableSymbol
          if (symbol != null && symbol.hasAnyAnnotation(options.allInjectAnnotations)) {
            addConsumer(declaration, symbol)
          }
        }
      }
      is KtNamedFunction -> {
        // Member/function injection: parameters are consumers
        if (declaration.isLocal || !processedMemberInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaNamedFunctionSymbol
          if (symbol != null && symbol.hasAnyAnnotation(options.allInjectAnnotations)) {
            for (parameter in declaration.valueParameters) {
              addParameterConsumer(parameter)
            }
          }
        }
      }
      else -> {}
    }
  }

  private fun processInjectClass(ktClass: KtClassOrObject) {
    if (!processedInjectClasses.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      // bindingData verifies injectability/contributions itself; classes without an explicit
      // primary constructor still provide their own type.
      val dataEntries = bindingData(ktClass, options)
      val consumerContributionScopes =
        dataEntries.flatMapTo(mutableSetOf()) { it.contributionScopes }
      for (data in dataEntries) {
        bindings += bindingFrom(ptr(ktClass), data)
      }
      // Gate the constructor consumers on the owning class's binding only when it originates one.
      // Assisted-injected classes provide no own-type binding (they're built via their factory), so
      // gating by origin would wrongly drop their dependencies from every graph.
      val originClassId = ktClass.getClassId().takeIf { dataEntries.isNotEmpty() }
      val injectConstructor = findInjectConstructor(ktClass, classSymbol)
      for (parameter in injectConstructor?.valueParameters.orEmpty()) {
        addParameterConsumer(
          parameter,
          originClassId = originClassId,
          contributionScopes = consumerContributionScopes,
        )
      }
    }
  }

  private fun processContribution(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedContributions.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      val scopeKeys = scopeKeysFor(classSymbol, options.allContributesAnnotations) ?: return@analyze
      contributions +=
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(ktClass),
          scopeKeys,
          ktClass.getClassId(),
        )
    }
    // Binding-like contributions also originate bindings (and constructor consumers when
    // contributesAsInject treats them as injected).
    processInjectClass(ktClass)
  }

  private fun processGraph(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedGraphs.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      val graphAnnotations =
        classSymbol.annotations.filter { it.classId in options.dependencyGraphAnnotations }
      val extensionAnnotations =
        classSymbol.annotations.filter { it.classId in options.graphExtensionAnnotations }
      val annotations = graphAnnotations + extensionAnnotations
      if (annotations.isEmpty()) return@analyze
      val scopeKeys = annotations.flatMapTo(mutableSetOf()) { annotationScopeKeys(it) }
      val excludes = annotations.flatMapTo(mutableSetOf()) { classListArgument(it, "excludes") }
      val containerIds =
        annotations.flatMapTo(mutableSetOf()) { classListArgument(it, "bindingContainers") }
      val graphClassId = ktClass.getClassId()
      val factoryAnnotations =
        options.dependencyGraphFactoryAnnotations + options.graphExtensionFactoryAnnotations
      val nestedClassIds = mutableSetOf<ClassId>()
      val includedDependencies = mutableSetOf<ClassId>()
      val extensionCreationIds = mutableSetOf<ClassId>()

      for (member in ktClass.declarations) {
        when (member) {
          is KtClassOrObject -> {
            val memberClassId = member.getClassId() ?: continue
            nestedClassIds += memberClassId
            // Factory @Includes params wire graph dependencies whose accessors join the graph
            val memberSymbol = member.symbol as? KaClassSymbol ?: continue
            if (!memberSymbol.hasAnyAnnotation(factoryAnnotations)) continue
            for (function in member.declarations.filterIsInstance<KtNamedFunction>()) {
              for (parameter in function.valueParameters) {
                val paramSymbol = parameter.symbol as? KaValueParameterSymbol ?: continue
                if (!paramSymbol.hasAnyAnnotation(setOf(MetroClassIds.includes))) continue
                val depType = paramSymbol.returnType.fullyExpandedType as? KaClassType ?: continue
                includedDependencies += depType.classId
                registerIncludedDependencyAccessors(depType.classId)
              }
            }
          }
          is KtCallableDeclaration -> {
            // Abstract accessor members are consumers of their return type.
            if (member is KtNamedFunction && member.valueParameters.isNotEmpty()) continue
            if (member !is KtNamedFunction && member !is KtProperty) continue
            if (member.receiverTypeReference != null) continue
            val symbol = member.symbol as? KaCallableSymbol ?: continue
            // @OptionalBinding accessors carry a default body, so they're concrete but still
            // consume.
            val isOptionalAccessor = isOptionalConsumer(symbol)
            if (symbol.modality != KaSymbolModality.ABSTRACT && !isOptionalAccessor) continue
            if (symbol.hasAnyAnnotation(nonAccessorCallableAnnotations(options))) continue
            if (symbol.returnType.isUnitType) continue
            // Accessors of a @GraphExtension (or its factory) are creation points of the child
            // graph, not dependencies on a binding; they only anchor the extension's parent chain.
            val returnClassType = symbol.returnType.fullyExpandedType as? KaClassType
            val returnClassSymbol = returnClassType?.symbol
            if (
              returnClassSymbol != null &&
                returnClassSymbol.hasAnyAnnotation(
                  options.graphExtensionAnnotations + options.graphExtensionFactoryAnnotations
                )
            ) {
              extensionCreationIds += returnClassType.classId
              continue
            }
            val site = consumedSite(symbol, options)
            consumers +=
              ConsumerEntry(
                ptr(member),
                site.key,
                site.isAbstractType,
                site.multibindingId,
                site.typeClassId,
                graphClassId = graphClassId,
                isOptional = isOptionalAccessor,
              )
          }
          else -> {}
        }
      }

      // Each aggregation scope implicitly conveys @SingleIn(scope) on the graph, alongside any
      // explicitly declared scope annotations
      val scopingAnnotations = buildSet {
        scopeKeys.mapTo(this, ::implicitSingleInAnnotation)
        addAll(scopeAnnotations(classSymbol, options))
      }

      graphs +=
        KaGraphNode(
          pointerManager.createSmartPsiElementPointer(ktClass),
          scopeKeys,
          classId = graphClassId,
          excludes = excludes,
          bindingContainers = containerIds,
          includedDependencies = includedDependencies,
          isExtension = graphAnnotations.isEmpty(),
          selfIds = setOfNotNull(graphClassId) + nestedClassIds,
          extensionCreationIds = extensionCreationIds,
          scopingAnnotations = scopingAnnotations,
        )
    }
  }

  /** `@Includes` graph dependencies expose their accessors as bindings in the including graph. */
  private fun KaSession.registerIncludedDependencyAccessors(depClassId: ClassId) {
    val depSymbol = findClass(depClassId) as? KaNamedClassSymbol ?: return
    for (callable in depSymbol.declaredMemberScope.callables) {
      if (callable !is KaPropertySymbol && callable !is KaNamedFunctionSymbol) continue
      if (callable is KaNamedFunctionSymbol && callable.valueParameters.isNotEmpty()) continue
      if (callable.returnType.isUnitType) continue
      val psi = callable.psi as? KtElement ?: continue
      bindings +=
        KaBinding(
          ptr(psi),
          typeKey(callable.returnType, qualifierAnnotation(callable, options)),
          BindingKind.INCLUDED,
          null,
          null,
          containerId = depClassId,
        )
    }
  }

  /** `@AssistedFactory` declarations provide their own type, creating their SAM's return type. */
  private fun processAssistedFactory(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedAssistedFactories.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      if (!classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)) return@analyze
      val samFunction =
        classSymbol.declaredMemberScope.callables
          .filterIsInstance<KaNamedFunctionSymbol>()
          .firstOrNull { it.modality == KaSymbolModality.ABSTRACT }
      val createdName =
        (samFunction?.returnType?.fullyExpandedType as? KaClassType)
          ?.classId
          ?.shortClassName
          ?.asString()
      bindings +=
        KaBinding(
          ptr(ktClass),
          typeKey(classSymbol.defaultType, qualifierAnnotation(classSymbol, options)),
          BindingKind.ASSISTED_FACTORY,
          scopeAnnotation(classSymbol, options),
          createdName,
          originClassId = ktClass.getClassId(),
        )
    }
  }

  /** `@BindingContainer` classes and the containers they transitively include. */
  private fun processBindingContainer(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedContainers.add(ktClass)) return
    val classId = ktClass.getClassId() ?: return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaClassSymbol ?: return@analyze
      val containerAnnotation =
        classSymbol.annotations.firstOrNull { it.classId in options.bindingContainerAnnotations }
          ?: return@analyze
      bindingContainerEntries +=
        BindingContainerEntry(
          classId,
          classListArgument(containerAnnotation, "includes").toSet(),
        )
    }
  }

  /**
   * Scope class ids from [annotated]'s annotations matching [annotationClassIds], or null when none
   * of the annotations are present.
   */
  private fun scopeKeysFor(
    annotated: KaAnnotated,
    annotationClassIds: Set<ClassId>,
  ): Set<ClassId>? {
    val annotations = annotated.annotations.filter { it.classId in annotationClassIds }
    if (annotations.isEmpty()) return null
    return annotations.flatMapTo(mutableSetOf()) { annotationScopeKeys(it) }
  }

  /**
   * Mirrors the compiler's Metro-native Circuit codegen (`CircuitContributionExtension` and
   * `CircuitFirExtension`): a `@CircuitInject(screen, scope)` declaration generates a factory
   * contributed into `Set<Ui.Factory>`/`Set<Presenter.Factory>` at the scope, with the
   * declaration's non-circuit-provided parameters injected through it.
   */
  private fun processCircuitInject(declaration: KtDeclaration) {
    when (declaration) {
      is KtNamedFunction -> {
        if (!declaration.isTopLevel || !processedCircuitInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaNamedFunctionSymbol ?: return@analyze
          val annotation =
            symbol.annotations.firstOrNull { it.classId == CircuitClassIds.CircuitInject }
              ?: return@analyze

          // Presenters return CircuitUiState subtypes; UI functions are Unit-returning Composables
          val factoryClassId =
            when {
              symbol.returnType.isUnitType -> CircuitClassIds.UiFactory
              isCircuitProvidedType(symbol.returnType) -> CircuitClassIds.PresenterFactory
              else -> return@analyze
            }

          val scopes = annotationScopeKeys(annotation)
          addCircuitContribution(declaration, scopes, factoryClassId)

          for (parameter in declaration.valueParameters) {
            addCircuitParameterConsumer(parameter, contributionScopes = scopes)
          }
        }
      }
      is KtClassOrObject -> {
        if (!processedCircuitInjects.add(declaration)) return
        analyze(declaration) {
          val classSymbol = declaration.symbol as? KaNamedClassSymbol ?: return@analyze
          val annotation =
            classSymbol.annotations.firstOrNull { it.classId == CircuitClassIds.CircuitInject }
              ?: return@analyze
          val supertypeIds =
            classSymbol.defaultType.allSupertypes.mapNotNull { (it as? KaClassType)?.classId }
          val factoryClassId =
            when {
              CircuitClassIds.Ui in supertypeIds -> CircuitClassIds.UiFactory
              CircuitClassIds.Presenter in supertypeIds -> CircuitClassIds.PresenterFactory
              else -> return@analyze
            }
          val scopes = annotationScopeKeys(annotation)
          addCircuitContribution(declaration, scopes, factoryClassId)
        }
        // Constructor dependencies are covered by the regular inject sweep when annotated
        processInjectClass(declaration)
      }
      else -> {}
    }
  }

  private fun KaSession.addCircuitContribution(
    declaration: KtDeclaration,
    scopes: Set<ClassId>,
    factoryClassId: ClassId,
  ) {
    contributions += ContributionEntry(ptr(declaration), scopes)
    val factoryType = (findClass(factoryClassId) as? KaNamedClassSymbol)?.defaultType ?: return
    val elementKey = typeKey(factoryType, null)
    bindings +=
      KaBinding(
        ptr(declaration),
        elementKey,
        BindingKind.MULTIBINDING_CONTRIBUTION,
        null,
        declaration.name,
        multibindingId = elementKey.computeMultibindingId(),
        contributionScopes = scopes,
      )
  }

  private fun KaSession.addCircuitParameterConsumer(
    parameter: KtParameter,
    contributionScopes: Set<ClassId>,
  ) {
    val symbol = parameter.symbol as? KaValueParameterSymbol ?: return
    if (symbol.hasAnyAnnotation(options.assistedAnnotations)) {
      assistedSites += AssistedSite(ptr(parameter), "@Assisted", isImplicit = false)
      return
    }
    if (isCircuitProvidedType(symbol.returnType)) {
      assistedSites += AssistedSite(ptr(parameter), "Circuit", isImplicit = true)
      return
    }
    addConsumer(parameter, symbol, contributionScopes = contributionScopes)
  }

  /**
   * Whether the type is supplied by Circuit at factory `create()` time rather than injected:
   * `Screen`/`CircuitUiState` subtypes, or exact `Navigator`/`Modifier`/`CircuitContext`.
   */
  private fun KaSession.isCircuitProvidedType(type: KaType): Boolean {
    val expanded = type.fullyExpandedType
    val classId = (expanded as? KaClassType)?.classId ?: return false
    when (classId) {
      CircuitClassIds.Navigator,
      CircuitClassIds.Modifier,
      CircuitClassIds.CircuitContext,
      CircuitClassIds.Screen,
      CircuitClassIds.CircuitUiState -> return true
      else -> {}
    }
    return expanded.allSupertypes.any { supertype ->
      val supertypeId = (supertype as? KaClassType)?.classId
      supertypeId == CircuitClassIds.Screen || supertypeId == CircuitClassIds.CircuitUiState
    }
  }

  private fun KaSession.findInjectConstructor(
    ktClass: KtClassOrObject,
    classSymbol: KaNamedClassSymbol,
  ): KtConstructor<*>? {
    // Non-injectable kinds have no graph-resolved constructor, so they originate no consumers.
    if (!classSymbol.isInjectableKind()) return null
    val injectish = options.allInjectAnnotations
    val classLevel =
      classSymbol.hasAnyAnnotation(injectish) ||
        (options.contributesAsInject &&
          classSymbol.annotations.any { it.classId in bindingContributionAnnotations(options) })
    val constructors = listOfNotNull(ktClass.primaryConstructor) + ktClass.secondaryConstructors
    val annotatedConstructor = constructors.firstOrNull { ctor ->
      ctor.symbol.hasAnyAnnotation(injectish)
    }
    return annotatedConstructor ?: if (classLevel) ktClass.primaryConstructor else null
  }

  private fun KaSession.addParameterConsumer(
    parameter: KtParameter,
    originClassId: ClassId? = null,
    contributionScopes: Set<ClassId> = emptySet(),
    containerId: ClassId? = null,
  ) {
    val symbol = parameter.symbol as? KaValueParameterSymbol ?: return
    if (symbol.hasAnyAnnotation(options.assistedAnnotations)) {
      assistedSites += AssistedSite(ptr(parameter), "@Assisted", isImplicit = false)
      return
    }
    if (symbol.hasAnyAnnotation(options.providesAnnotations)) return // instance binding param
    addConsumer(
      parameter,
      symbol,
      originClassId = originClassId,
      contributionScopes = contributionScopes,
      containerId = containerId,
    )
  }

  private fun KaSession.addConsumer(
    element: KtElement,
    symbol: KaCallableSymbol,
    originClassId: ClassId? = null,
    contributionScopes: Set<ClassId> = emptySet(),
    containerId: ClassId? = null,
  ) {
    val site = consumedSite(symbol, options)
    consumers +=
      ConsumerEntry(
        ptr(element),
        site.key,
        site.isAbstractType,
        site.multibindingId,
        site.typeClassId,
        originClassId = originClassId,
        contributionScopes = contributionScopes,
        containerId = containerId,
        isOptional = isOptionalConsumer(symbol),
      )
  }

  /**
   * Whether a consumer permits absence: a native `@OptionalBinding`/`@OptionalDependency` marker,
   * or a defaulted parameter under `DEFAULT` behavior. Under `REQUIRE_OPTIONAL_BINDING` only the
   * annotation counts; `DISABLED` never treats a site as optional.
   */
  private fun isOptionalConsumer(symbol: KaCallableSymbol): Boolean {
    val behavior = options.optionalBindingBehavior
    if (behavior == OptionalBindingBehavior.DISABLED) return false
    if (symbol.hasAnyAnnotation(options.optionalBindingAnnotations)) return true
    if (!behavior.requiresAnnotatedParameters) {
      return (symbol as? KaValueParameterSymbol)?.hasDefaultValue == true
    }
    return false
  }

  private fun KtCallableDeclaration.hasAnyOfAnnotations(classIds: Set<ClassId>): Boolean {
    // Cheap PSI pre-check by short name only; used to avoid registering @Binds params as consumers
    // twice (the analysis-verified path already handles them).
    val shortNames = classIds.mapTo(mutableSetOf()) { it.shortClassName.asString() }
    return annotationEntries.any { it.shortName?.asString() in shortNames }
  }
}
