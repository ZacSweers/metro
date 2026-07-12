// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.implicitSingleInAnnotation
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.aggregateMultibindingId
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import dev.zacsweers.metro.idea.scopeAnnotations
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

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
  private val factoryInputs: MutableList<FactoryInputEntry> = mutableListOf(),
) {
  private val pointerManager = SmartPointerManager.getInstance(project)

  private val processedBindingCallables = HashSet<KtDeclaration>()
  private val processedInjectClasses = HashSet<KtClassOrObject>()
  private val processedMemberInjects = HashSet<KtDeclaration>()
  private val processedContributions = HashSet<KtClassOrObject>()
  private val processedGraphs = HashSet<KtClassOrObject>()
  private val processedCircuitInjects = HashSet<KtDeclaration>()
  private val processedAssistedFactories = HashSet<KtClassOrObject>()
  private val processedContainers = HashSet<KtClassOrObject>()
  private val processedFactoryInputs = HashSet<FactoryInputEntry.Id>()
  private val cacheDependencies = HashSet<PsiFile>()

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
      factoryInputs,
      cacheDependencies,
    )
  }

  /** Runs the cross-file library passes over previously merged shard accumulators. */
  fun postProcess() {
    LibraryIndexPostProcessor(project, options, bindings, consumers, graphs, contributions)
      .postProcess()
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  /** Companion members belong to the enclosing container class, mirroring the compiler. */
  private fun KtClassOrObject.containerClassId(): ClassId? {
    return if (this is KtObjectDeclaration && isCompanion()) {
      containingClassOrObject?.getClassId() ?: getClassId()
    } else {
      getClassId()
    }
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
              is KtCallableDeclaration -> target.containingClassOrObject?.containerClassId()
              else -> null
            }
          val dataEntries = bindingData(target, options)
          val consumerOriginClassId = dataEntries.firstNotNullOfOrNull { it.originClassId }
          val consumerContributionScopes = dataEntries.flatMapToSet { it.contributionScopes }
          val ownerDependency = graphOwnerDependency(target)
          for (data in dataEntries) {
            bindings +=
              data.toKaBinding(
                ptr(target),
                containerId = containerId,
                ownerDependency = ownerDependency,
              )
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
          if (target is KtNamedFunction && !target.isAnnotatedWithAny(options.bindsAnnotations)) {
            for (parameter in target.valueParameters) {
              addParameterConsumer(
                parameter,
                originClassId = consumerOriginClassId,
                contributionScopes = consumerContributionScopes,
                containerId = containerId,
              )
            }
            // An extension receiver on a provider function is a dependency too.
            val receiverRef = target.receiverTypeReference
            val receiverSymbol = (target.symbol as? KaCallableSymbol)?.receiverParameter
            if (receiverRef != null && receiverSymbol != null) {
              addConsumer(
                receiverRef,
                receiverSymbol,
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

  private fun KaSession.graphOwnerDependency(target: KtDeclaration): KaContextualTypeKey? {
    val callable = target as? KtCallableDeclaration ?: return null
    val container = callable.containingClassOrObject ?: return null
    if (container is KtObjectDeclaration) return null
    val symbol = container.symbol as? KaNamedClassSymbol ?: return null
    val graphAnnotations = options.dependencyGraphAnnotations + options.graphExtensionAnnotations
    if (!symbol.hasAnyAnnotation(graphAnnotations)) return null
    return typeKey(symbol.defaultType, qualifier = null).canonicalContextKey()
  }

  /** `@Inject`/`@AssistedInject` on classes, constructors, and members. */
  private fun processInjectAnnotated(declaration: KtDeclaration) {
    when (declaration) {
      is KtConstructor<*> -> processInjectClass(declaration.getContainingClassOrObject())
      is KtClassOrObject -> processInjectClass(declaration)
      is KtProperty -> {
        // Member injection site. @Inject has no PROPERTY target, so also check the backing field
        // and setter.
        if (declaration.isLocal || !processedMemberInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaPropertySymbol ?: return@analyze
          val injectIds = options.allInjectAnnotations
          val injected =
            symbol.hasAnyAnnotation(injectIds) ||
              symbol.backingFieldSymbol?.hasAnyAnnotation(injectIds) == true ||
              symbol.setter?.hasAnyAnnotation(injectIds) == true
          if (injected) {
            addConsumer(declaration, symbol)
          }
        }
      }
      is KtNamedFunction -> {
        if (declaration.isLocal || !processedMemberInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaNamedFunctionSymbol ?: return@analyze
          if (!symbol.hasAnyAnnotation(options.allInjectAnnotations)) return@analyze
          if (declaration.isTopLevel) {
            processInjectFunction(declaration, symbol)
          } else {
            // Member injection site: parameters are consumers
            for (parameter in declaration.valueParameters) {
              addParameterConsumer(parameter)
            }
          }
        }
      }
      else -> {}
    }
  }

  /**
   * Top-level function injection: `@Inject fun App(...)` generates an injectable class named after
   * the function. Non-assisted parameters are the class's constructor dependencies; assisted
   * parameters move to the generated class's `invoke`.
   */
  private fun KaSession.processInjectFunction(
    function: KtNamedFunction,
    symbol: KaNamedFunctionSymbol,
  ) {
    val name = function.name ?: return
    val classId = ClassId(function.containingKtFile.packageFqName, Name.identifier(name))
    val typeKey = KaTypeKey(KaTypeSnapshot(classId.asSingleFqName().asString(), name, classId))
    val dependencies =
      symbol.valueParameters
        .filterNot { it.hasAnyAnnotation(options.assistedAnnotations) }
        .map { dependencyKey(it, options) }
    bindings +=
      KaBinding.ConstructorInjected(
        pointer = ptr(function),
        typeKey = typeKey,
        scope = scopeAnnotation(symbol, options),
        implementationName = name,
        originClassId = classId,
        dependencies = dependencies,
      )
    for (parameter in function.valueParameters) {
      addParameterConsumer(parameter, originClassId = classId)
    }
  }

  private fun processInjectClass(ktClass: KtClassOrObject) {
    if (!processedInjectClasses.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      // bindingData verifies injectability/contributions itself; classes without an explicit
      // primary constructor still provide their own type.
      val dataEntries = bindingData(ktClass, options)
      val consumerContributionScopes = dataEntries.flatMapToSet { it.contributionScopes }
      for (data in dataEntries) {
        bindings += data.toKaBinding(ptr(ktClass))
      }
      // Gate the constructor consumers on the owning class's binding only when it originates one.
      // Assisted-injected classes provide no own-type binding (they're built via their factory), so
      // gating by origin would wrongly drop their dependencies from every graph.
      val originClassId = ktClass.getClassId().takeIf { dataEntries.isNotEmpty() }
      val injectConstructor = findInjectConstructor(ktClass, classSymbol, options)
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
      val scopeKeys = classSymbol.scopeKeys(options.allContributesAnnotations) ?: return@analyze
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
      val scopeKeys = annotations.flatMapToSet { annotationScopeKeys(it) }
      val excludes = annotations.flatMapToSet { classListArgument(it, "excludes") }
      val containerIds = annotations.flatMapToSet { classListArgument(it, "bindingContainers") }
      val graphClassId = ktClass.getClassId()
      val graphPointer = pointerManager.createSmartPsiElementPointer(ktClass)
      val graphId = GraphDeclarationId(graphClassId, graphPointer.virtualFile)
      val factoryAnnotations =
        options.dependencyGraphFactoryAnnotations + options.graphExtensionFactoryAnnotations
      val nestedClassIds = mutableSetOf<ClassId>()
      val includedBindingContainers = mutableSetOf<KaTypeKey>()
      val includedDependencies = mutableSetOf<KaTypeKey>()
      val extensionCreationIds = mutableSetOf<ClassId>()

      for (member in ktClass.declarations) {
        when (member) {
          is KtClassOrObject -> {
            val memberClassId = member.getClassId() ?: continue
            nestedClassIds += memberClassId
            val memberSymbol = member.symbol as? KaClassSymbol ?: continue
            if (!memberSymbol.hasAnyAnnotation(factoryAnnotations)) continue
            val includes = factoryIncludes(memberSymbol, options, pointerManager)
            cacheDependencies += includes.cacheDependencies
            includedBindingContainers += includes.bindingContainers
            includedDependencies += includes.graphDependencies
            for (input in includes.inputs) {
              if (processedFactoryInputs.add(input.id)) {
                factoryInputs += input
              }
            }
          }
          is KtCallableDeclaration -> {
            // Members with parameters are injector candidates, not accessors.
            if (member is KtNamedFunction && member.valueParameters.isNotEmpty()) {
              processGraphInjector(member, graphId)
              continue
            }
            if (member !is KtNamedFunction && member !is KtProperty) continue
            if (member.receiverTypeReference != null) continue
            val symbol = member.symbol as? KaCallableSymbol ?: continue
            // @OptionalBinding accessors carry a default body, so they're concrete but still
            // consume.
            val isOptionalAccessor = symbol.isOptionalConsumer(options)
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
                site.contextKey,
                site.isAbstractType,
                site.multibindingId,
                site.typeClassId,
                graphId = graphId,
                isOptional = isOptionalAccessor,
              )
          }
          else -> {}
        }
      }

      // Supertype members merge into the graph, mirroring the compiler. Their accessors become
      // this graph's consumers and their class ids gate their providers' membership.
      val supertypeIds = mutableSetOf<ClassId>()
      for (superType in classSymbol.defaultType.allSupertypes) {
        if (superType.isAnyType) continue
        val superClass = (superType as? KaClassType)?.symbol as? KaNamedClassSymbol ?: continue
        val superClassId = superClass.classId ?: continue
        if (!supertypeIds.add(superClassId)) continue
        indexSupertypeMembers(superClass, graphId, extensionCreationIds)
      }

      // Each aggregation scope implicitly conveys @SingleIn(scope) on the graph, alongside any
      // explicitly declared scope annotations
      val scopingAnnotations = buildSet {
        scopeKeys.mapTo(this, ::implicitSingleInAnnotation)
        addAll(scopeAnnotations(classSymbol, options))
      }

      graphs +=
        KaGraphNode(
          graphPointer,
          scopeKeys,
          classId = graphClassId,
          excludes = excludes,
          bindingContainers = containerIds,
          includedBindingContainers = includedBindingContainers,
          includedDependencies = includedDependencies,
          isExtension = graphAnnotations.isEmpty(),
          selfIds = setOfNotNull(graphClassId) + nestedClassIds,
          supertypeIds = supertypeIds,
          extensionCreationIds = extensionCreationIds,
          scopingAnnotations = scopingAnnotations,
        )
    }
  }

  /** Indexes a graph supertype's accessors and injectors as members of the merging graph. */
  private fun KaSession.indexSupertypeMembers(
    superClass: KaNamedClassSymbol,
    graphId: GraphDeclarationId,
    extensionCreationIds: MutableSet<ClassId>,
  ) {
    // The source annotation sweep never sees library files, so a library supertype's binding
    // callables index here through their decompiled declarations
    val isLibrary = superClass.origin == KaSymbolOrigin.LIBRARY
    val bindingCallableIds =
      options.providesAnnotations +
        options.bindsAnnotations +
        options.multibindsAnnotations +
        bindsOptionalOfAnnotations(options)
    for (callable in superClass.declaredMemberScope.callables) {
      if (isLibrary && callable.hasAnyAnnotation(bindingCallableIds)) {
        (callable.psi as? KtDeclaration)?.let { processBindingCallable(it) }
        continue
      }
      if (callable is KaNamedFunctionSymbol && callable.valueParameters.isNotEmpty()) {
        (callable.psi as? KtNamedFunction)?.let { processGraphInjector(it, graphId) }
        continue
      }
      if (callable !is KaNamedFunctionSymbol && callable !is KaPropertySymbol) continue
      if (callable.receiverParameter != null) continue
      val isOptionalAccessor = callable.isOptionalConsumer(options)
      if (callable.modality != KaSymbolModality.ABSTRACT && !isOptionalAccessor) continue
      if (callable.hasAnyAnnotation(nonAccessorCallableAnnotations(options))) continue
      if (callable.returnType.isUnitType) continue
      val returnClassType = callable.returnType.fullyExpandedType as? KaClassType
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
      val psi = callable.psi as? KtElement ?: continue
      val site = consumedSite(callable, options)
      consumers +=
        ConsumerEntry(
          ptr(psi),
          site.contextKey,
          site.isAbstractType,
          site.multibindingId,
          site.typeClassId,
          graphId = graphId,
          isOptional = isOptionalAccessor,
        )
    }
  }

  /**
   * Indexes a graph injector member such as `fun inject(target: Foo)`. Each of the target's
   * member-inject keys becomes a consumer anchored at the injector.
   */
  private fun KaSession.processGraphInjector(member: KtNamedFunction, graphId: GraphDeclarationId) {
    if (member.valueParameters.size != 1) return
    val symbol = member.symbol as? KaNamedFunctionSymbol ?: return
    if (symbol.modality != KaSymbolModality.ABSTRACT) return
    if (!symbol.returnType.isUnitType) return
    if (symbol.hasAnyAnnotation(nonAccessorCallableAnnotations(options))) return
    val targetType =
      symbol.valueParameters.single().returnType.fullyExpandedType as? KaClassType ?: return
    val targetSymbol = targetType.symbol as? KaNamedClassSymbol ?: return
    for (contextKey in memberInjectDependencyKeys(targetSymbol, options)) {
      consumers +=
        ConsumerEntry(
          ptr(member),
          contextKey,
          multibindingId = contextKey.aggregateMultibindingId(options),
          typeClassId = contextKey.typeKey.type.classId,
          graphId = graphId,
          isOptional = contextKey.hasDefault,
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
      val createdClassSymbol =
        (samFunction?.returnType?.fullyExpandedType as? KaClassType)?.symbol as? KaNamedClassSymbol
      val createdName = createdClassSymbol?.classId?.shortClassName?.asString()
      // The factory constructs its target directly, so it inherits the target's graph-provided
      // dependencies. The assisted class itself has no own-type binding.
      val targetDependencies =
        createdClassSymbol?.let { injectClassDependencyKeys(it, options) }.orEmpty()
      bindings +=
        KaBinding.AssistedFactory(
          ptr(ktClass),
          typeKey(classSymbol.defaultType, qualifierAnnotation(classSymbol, options)),
          scopeAnnotation(classSymbol, options),
          createdName,
          originClassId = ktClass.getClassId(),
          dependencies = targetDependencies,
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
          // The generated factory injects the declaration's non-circuit-provided, non-assisted
          // parameters.
          val dependencies =
            symbol.valueParameters
              .filterNot { it.hasAnyAnnotation(options.assistedAnnotations) }
              .filterNot { isCircuitProvidedType(it.returnType) }
              .map { dependencyKey(it, options) }
          addCircuitContribution(declaration, scopes, factoryClassId, dependencies)

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
          // The generated factory constructs the class, injecting its non-circuit-provided,
          // non-assisted constructor parameters.
          val dependencies =
            findInjectConstructorSymbol(classSymbol, options)
              ?.valueParameters
              .orEmpty()
              .filterNot { it.hasAnyAnnotation(options.assistedAnnotations) }
              .filterNot { isCircuitProvidedType(it.returnType) }
              .map { dependencyKey(it, options) }
          addCircuitContribution(declaration, scopes, factoryClassId, dependencies)
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
    dependencies: List<KaContextualTypeKey>,
  ) {
    contributions += ContributionEntry(ptr(declaration), scopes)
    val factoryType = (findClass(factoryClassId) as? KaNamedClassSymbol)?.defaultType ?: return
    val elementKey = typeKey(factoryType, null)
    bindings +=
      KaBinding.Provided(
        ptr(declaration),
        elementKey,
        implementationName = declaration.name,
        multibindingId = elementKey.computeMultibindingId(),
        contributionScopes = scopes,
        dependencies = dependencies,
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
        site.contextKey,
        site.isAbstractType,
        site.multibindingId,
        site.typeClassId,
        originClassId = originClassId,
        contributionScopes = contributionScopes,
        containerId = containerId,
        isOptional = symbol.isOptionalConsumer(options),
      )
  }
}
