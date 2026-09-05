// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.graph.GraphDeclarationExtractor
import dev.zacsweers.metro.idea.index.graph.GraphMemberExtractor
import dev.zacsweers.metro.idea.index.graph.containerClassId
import dev.zacsweers.metro.idea.index.graph.graphExtensionFactoryTarget
import dev.zacsweers.metro.idea.index.graph.graphReference
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DynamicGraphCall
import dev.zacsweers.metro.idea.model.DynamicGraphId
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.model.multibindingId
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import dev.zacsweers.metro.idea.scopeAnnotations
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkItem
import dev.zacsweers.metro.idea.tracing.stage
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
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
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/** Extracts the Metro declarations from one file into a cacheable [FileShard]. */
internal class FileShardBuilder(
  private val project: Project,
  private val options: MetroOptions,
) {
  private val bindings = mutableListOf<KaBinding>()
  private val consumers = mutableListOf<ConsumerEntry>()
  private val graphs = mutableListOf<KaGraphDeclaration>()
  private val contributions = mutableListOf<ContributionEntry>()
  private val assistedSites = mutableListOf<AssistedSite>()
  private val bindingContainerEntries = mutableListOf<BindingContainerEntry>()
  private val factoryInputs = mutableListOf<FactoryInputEntry>()
  private val dynamicGraphs = mutableListOf<DynamicGraphCall>()
  private val graphInterfaces = mutableListOf<GraphInterfaceSurface>()
  private val pointerManager = SmartPointerManager.getInstance(project)

  private val processedBindingCallables = HashSet<KtDeclaration>()
  private val processedInjectClasses = HashSet<KtClassOrObject>()
  private val processedMemberInjects = HashSet<KtDeclaration>()
  private val processedContributions = HashSet<KtClassOrObject>()
  private val processedGraphs = HashSet<KtClassOrObject>()
  private val processedCircuitInjects = HashSet<KtDeclaration>()
  private val processedAssistedFactories = HashSet<KtClassOrObject>()
  private val processedAssistedFactoryTypes = HashSet<AssistedFactoryIdentity>()
  private val processedContainers = HashSet<KtClassOrObject>()
  private val processedFactoryInputs = HashSet<FactoryInputEntry.Id>()
  private val processedDynamicGraphs = HashSet<DynamicGraphId>()
  private val cacheDependencies = HashSet<PsiFile>()
  private val sharedDeclarationDependencies = HashSet<PsiFile>()
  private val graphMembers =
    GraphMemberExtractor(
      options,
      pointerManager,
      bindings,
      cacheDependencies::add,
      { annotated, useSite -> recordAnnotationDependencies(annotated, useSite) },
      { type -> processRequestedAssistedFactory(type) },
    )
  private val graphDeclarations =
    GraphDeclarationExtractor(
      options,
      pointerManager,
      graphMembers,
      consumers,
      ::addGraphFactoryInput,
      cacheDependencies::add,
      { annotated, useSite -> recordAnnotationDependencies(annotated, useSite) },
    )
  private var cancellationWorkIndex = 0

  private fun checkCanceled() {
    checkCanceledEvery(cancellationWorkIndex++)
  }

  /**
   * The PSI files backing [FileShard.dependencyFiles], for the caller's CachedValue registration.
   * Read once after [buildShard]. The shard model itself never retains PSI.
   */
  val psiDependencies: Set<PsiFile>
    get() = cacheDependencies

  /** Stage measurements stay with this invocation; shards and cached providers retain no trace. */
  fun buildShard(file: KtFile, trace: IdeTraceWorkItem? = null): FileShard {
    // Read imports once per shard. Most files have no aliases, so their annotation groups retain
    // the original short-name-only path without seven repeated PSI import walks.
    val aliasedImports =
      trace.stage("source.file.imports") {
        val imports = mutableMapOf<FqName, MutableSet<String>>()
        for (directive in file.importDirectives) {
          checkCanceled()
          val alias = directive.aliasName ?: continue
          val importedName = directive.importedFqName ?: continue
          imports.getOrPut(importedName, ::mutableSetOf) += alias
        }
        imports
      }

    fun annotationNames(annotationIds: Set<ClassId>): Set<String> {
      val names = shortNames(annotationIds)
      if (aliasedImports.isEmpty()) return names
      return buildSet {
        addAll(names)
        for (annotationId in annotationIds) {
          addAll(aliasedImports[annotationId.asSingleFqName()].orEmpty())
        }
      }
    }

    val bindingCallableNames =
      annotationNames(
        options.providesAnnotations +
          options.bindsAnnotations +
          options.multibindsAnnotations +
          bindsOptionalOfAnnotations(options)
      )
    val injectNames = annotationNames(options.injectAnnotations + options.assistedInjectAnnotations)
    val contributesNames = annotationNames(options.allContributesAnnotations)
    val graphNames =
      annotationNames(options.dependencyGraphAnnotations + options.graphExtensionAnnotations)
    val assistedFactoryNames = annotationNames(options.assistedFactoryAnnotations)
    val containerNames = annotationNames(options.bindingContainerAnnotations)
    val circuitNames = annotationNames(setOf(CircuitClassIds.CircuitInject))
    val knownAnnotationNames =
      bindingCallableNames +
        injectNames +
        contributesNames +
        graphNames +
        assistedFactoryNames +
        containerNames +
        circuitNames
    val dynamicGraphNames = buildSet {
      for (callableId in DYNAMIC_GRAPH_CALLABLES.keys) {
        add(callableId.callableName.asString())
        addAll(aliasedImports[callableId.asSingleFqName()].orEmpty())
      }
    }

    trace.stage("source.file.annotationScan") {
      PsiTreeUtil.processElements(file) { element ->
        checkCanceled()
        val entry = element as? KtAnnotationEntry ?: return@processElements true
        val writtenName = entry.shortName?.asString() ?: return@processElements true
        // Typealiases can introduce other spellings whose meaning depends on the current scope.
        val shortName =
          trace.stage("source.file.annotationLookup") {
            if (writtenName in knownAnnotationNames) writtenName
            else {
              val typeReference = entry.typeReference ?: return@processElements true
              trace.stage("source.file.typealiasLookup") {
                analyze(typeReference) {
                  val type = typeReference.type.fullyExpandedType as? KaClassType
                  type?.classId?.shortClassName?.asString() ?: writtenName
                }
              }
            }
          }
        val declaration =
          entry.getStrictParentOfType<KtDeclaration>() ?: return@processElements true
        trace.stage("source.file.declarationExtraction") {
          if (shortName in bindingCallableNames) processBindingCallable(declaration)
          if (shortName in injectNames) processInjectAnnotated(declaration)
          if (shortName in contributesNames) processContribution(declaration)
          if (shortName in graphNames) processGraph(declaration)
          if (shortName in assistedFactoryNames) processAssistedFactory(declaration)
          if (shortName in containerNames) processBindingContainer(declaration)
          if (options.enableCircuitCodegen && shortName in circuitNames) {
            processCircuitInject(declaration)
          }
        }
        true
      }
    }
    trace.stage("source.file.dynamicGraphScan") {
      PsiTreeUtil.processElements(file) { element ->
        checkCanceled()
        val call = element as? KtCallExpression ?: return@processElements true
        val name = call.calleeExpression?.text ?: return@processElements true
        if (name in dynamicGraphNames) processDynamicGraphCall(call)
        true
      }
    }
    return trace.stage("source.file.shardConstruction") {
      FileShard(
        bindings,
        consumers,
        graphs,
        contributions,
        assistedSites,
        bindingContainerEntries,
        factoryInputs,
        dynamicGraphs,
        cacheDependencies.mapNotNullTo(mutableSetOf()) { it.virtualFile },
        sharedDeclarationDependencies.mapNotNullTo(mutableSetOf()) { it.virtualFile },
        graphInterfaces,
      )
    }
  }

  private fun processDynamicGraphCall(call: KtCallExpression) {
    analyze(call) {
      val function =
        call.resolveToCall()?.successfulFunctionCallOrNull()?.signature?.symbol ?: return@analyze
      val isFactory = DYNAMIC_GRAPH_CALLABLES[function.callableId] ?: return@analyze
      val requestedType = call.expressionType?.fullyExpandedType as? KaClassType ?: return@analyze
      val requestedClass = requestedType.symbol as? KaNamedClassSymbol ?: return@analyze
      val targetGraphType =
        if (isFactory) {
          assistedFactoryFunction(requestedType)?.returnType?.fullyExpandedType as? KaClassType
            ?: return@analyze
        } else {
          requestedType
        }
      val targetGraphClass = targetGraphType.symbol as? KaNamedClassSymbol ?: return@analyze
      if (!targetGraphClass.hasAnyAnnotation(options.dependencyGraphAnnotations)) return@analyze
      val targetGraphFile = targetGraphClass.psi?.containingFile
      targetGraphFile?.let(cacheDependencies::add)
      requestedClass.psi?.containingFile?.let(cacheDependencies::add)

      val callerFile = call.containingKtFile.virtualFile ?: return@analyze
      val containerKeys = linkedSetOf<KaTypeKey>()
      val inputs = mutableListOf<FactoryInputEntry>()
      for (argument in call.valueArguments) {
        checkCanceled()
        val expression = argument.getArgumentExpression() ?: continue
        val containerType = expression.expressionType?.fullyExpandedType as? KaClassType ?: continue
        val containerClass = containerType.symbol as? KaNamedClassSymbol ?: continue
        if (!containerClass.hasAnyAnnotation(options.bindingContainerAnnotations)) continue
        val containerKey = typeKey(containerType, qualifier = null)
        if (!containerKeys.add(containerKey)) continue
        val input =
          bindingContainerInput(
            containerType,
            containerKey,
            expression,
            options,
            pointerManager,
            cacheDependencies,
            GraphDeclarationId(targetGraphType.classId, targetGraphFile?.virtualFile),
          )
        inputs += input
      }
      if (containerKeys.isEmpty()) return@analyze
      val id = DynamicGraphId(requestedType.classId, containerKeys.toSet(), callerFile)
      if (!processedDynamicGraphs.add(id)) return@analyze
      for (input in inputs) {
        checkCanceled()
        val inputBinding = input.bindings.firstOrNull()
        if (inputBinding is KaBinding.BoundInstance) {
          bindings += inputBinding
        }
        if (processedFactoryInputs.add(input.id)) {
          factoryInputs += input
        }
      }
      val bindingKeys =
        inputs
          .asSequence()
          .flatMap { it.bindings.asSequence() }
          .filterNot { it is KaBinding.BoundInstance || it.multibindingId != null }
          .mapTo(linkedSetOf()) { it.typeKey }
      dynamicGraphs +=
        DynamicGraphCall(
          pointerManager.createSmartPsiElementPointer(call),
          id,
          targetGraphType.graphReference(),
          bindingKeys,
          inputs.mapNotNull { it.bindings.firstOrNull() as? KaBinding.BoundInstance },
          isFactory,
        )
    }
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
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
          (target.symbol as? KaAnnotated)?.let { recordAnnotationDependencies(it, target) }
          val dataEntries = target.bindingData(this, options)
          val consumerOriginClassId = dataEntries.firstNotNullOfOrNull { it.originClassId }
          val consumerContributionScopes = dataEntries.flatMapToSet { it.contributionScopes }
          val ownerDependency = graphOwnerDependency(target)
          for (data in dataEntries) {
            checkCanceled()
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
              checkCanceled()
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
            addConsumer(
              declaration,
              symbol,
              memberOwnerClassId = declaration.containingClassOrObject?.getClassId(),
            )
          }
        }
      }
      is KtNamedFunction -> {
        if (declaration.isLocal || !processedMemberInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaNamedFunctionSymbol ?: return@analyze
          if (!symbol.hasAnyAnnotation(options.allInjectAnnotations)) return@analyze
          if (declaration.isTopLevel) {
            // The compiler only generates injectable classes for top-level inject functions when
            // the option is on, so an indexed binding would be a phantom otherwise.
            if (options.enableTopLevelFunctionInjection) {
              processInjectFunction(declaration, symbol)
            }
          } else {
            // Member injection site: parameters are consumers
            for (parameter in declaration.valueParameters) {
              checkCanceled()
              addParameterConsumer(
                parameter,
                memberOwnerClassId = declaration.containingClassOrObject?.getClassId(),
              )
            }
          }
        }
      }
      else -> {}
    }
  }

  /**
   * Top-level function injection generates an injectable class with the function's capitalized
   * name. Non-assisted parameters are the class's constructor dependencies; assisted parameters
   * move to the generated class's `invoke`.
   */
  private fun KaSession.processInjectFunction(
    function: KtNamedFunction,
    symbol: KaNamedFunctionSymbol,
  ) {
    val name = function.name?.capitalizeUS() ?: return
    val classId = ClassId(function.containingKtFile.packageFqName, Name.identifier(name))
    val typeKey = KaTypeKey(KaTypeSnapshot(classId.asSingleFqName().asString(), name, classId))
    val dependencies =
      symbol.valueParameters
        .onEach { checkCanceled() }
        .filterNot { it.hasAnyAnnotation(options.assistedAnnotations) }
        .map { dependencyKey(it, options) }
    bindings +=
      KaBinding.ConstructorInjected(
        pointer = ptr(function),
        typeKey = typeKey,
        scope = scopeAnnotation(symbol, options),
        implementationName = name,
        originClassId = classId,
        constructorDependencies = dependencies,
      )
    for (parameter in function.valueParameters) {
      checkCanceled()
      addParameterConsumer(parameter, originClassId = classId)
    }
  }

  private fun processInjectClass(ktClass: KtClassOrObject) {
    if (!processedInjectClasses.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      recordAnnotationDependencies(classSymbol, ktClass)
      // bindingData verifies injectability/contributions itself; classes without an explicit
      // primary constructor still provide their own type.
      val dataEntries = ktClass.bindingData(this, options, cacheDependencies::add)
      val consumerContributionScopes = dataEntries.flatMapToSet { it.contributionScopes }
      for (data in dataEntries) {
        checkCanceled()
        bindings += data.toKaBinding(ptr(ktClass))
      }
      // Gate constructor consumers on the owning class's binding only when it originates one.
      val originClassId = ktClass.getClassId().takeIf { dataEntries.isNotEmpty() }
      val injectConstructor = findInjectConstructor(ktClass, classSymbol, options)
      for (parameter in injectConstructor?.valueParameters.orEmpty()) {
        checkCanceled()
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
      recordAnnotationDependencies(classSymbol, ktClass)
      val kind = classSymbol.contributionKind(options)
      val replaces =
        classSymbol.annotations
          .filter { it.classId in options.allContributesAnnotations }
          .flatMapToSet { classListArgument(it, "replaces") }
      val factoryType = classSymbol.defaultType as? KaClassType
      val childType =
        if (classSymbol.hasAnyAnnotation(options.graphExtensionFactoryAnnotations)) {
          factoryType?.let { graphExtensionFactoryTarget(it, options, cacheDependencies::add) }
        } else {
          null
        }
      val contribution =
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(ktClass),
          scopeKeys,
          ktClass.getClassId(),
          kind = kind,
          replaces = replaces,
          graphExtension = childType?.graphReference(),
        )
      contributions += contribution
      if (kind == ContributionEntry.Kind.GRAPH_INTERFACE && factoryType != null) {
        graphInterfaces += graphMembers.interfaceSurface(this, contribution, factoryType)
      }
    }
    // Binding-like contributions also originate bindings (and constructor consumers when
    // contributesAsInject treats them as injected).
    processInjectClass(ktClass)
  }

  private fun processGraph(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedGraphs.add(ktClass)) return
    analyze(ktClass) {
      val graph = graphDeclarations.extract(this, ktClass) ?: return@analyze
      graphs += graph
    }
  }

  /** Each graph keeps its bound input; shared container and dependency members are merged once. */
  private fun addGraphFactoryInput(input: FactoryInputEntry) {
    val inputBinding = input.bindings.firstOrNull()
    if (inputBinding is KaBinding.BoundInstance) {
      bindings += inputBinding
    }
    if (processedFactoryInputs.add(input.id)) {
      factoryInputs += input
    }
  }

  /** `@AssistedFactory` declarations provide their own type, creating their SAM's return type. */
  private fun processAssistedFactory(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedAssistedFactories.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      if (!classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)) return@analyze
      recordAnnotationDependencies(classSymbol, ktClass)
      val factoryType = classSymbol.defaultType as? KaClassType ?: return@analyze
      indexAssistedFactory(ktClass, classSymbol, factoryType)
    }
  }

  /** A generic source factory is materialized for the concrete type its use site requests. */
  private fun KaSession.processRequestedAssistedFactory(type: KaType) {
    var factoryType = type.fullyExpandedType as? KaClassType ?: return
    while (true) {
      checkCanceled()
      val classId = factoryType.classId
      val isWrapper =
        classId in options.providerTypes ||
          classId in options.lazyTypes ||
          classId in options.suspendProviderModelingTypes ||
          classId in options.suspendLazyTypes
      if (!isWrapper) break
      factoryType =
        factoryType.typeArguments.firstOrNull()?.type?.fullyExpandedType as? KaClassType ?: return
    }
    val classSymbol = factoryType.symbol as? KaNamedClassSymbol ?: return
    if (classSymbol.origin == KaSymbolOrigin.LIBRARY) return
    if (!classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)) return
    val declaration = classSymbol.psi as? KtClassOrObject ?: return
    cacheDependencies.add(declaration.containingFile)
    recordAnnotationDependencies(classSymbol, declaration)
    indexAssistedFactory(declaration, classSymbol, factoryType)
  }

  private fun KaSession.indexAssistedFactory(
    declaration: KtClassOrObject,
    classSymbol: KaNamedClassSymbol,
    factoryType: KaClassType,
  ) {
    val factoryKey = typeKey(factoryType, qualifierAnnotation(classSymbol, options))
    if (!processedAssistedFactoryTypes.add(AssistedFactoryIdentity(declaration, factoryKey))) return

    // The factory constructs its target directly, so its target dependencies use the actual type
    // arguments requested by the graph instead of the target class's unspecialized default type.
    // Keep only this direct request in the shard. A shared post-merge worklist follows factory
    // dependencies once, with the requesting graph's module and a generic-growth guard.
    val binding =
      assistedFactoryBinding(
        classSymbol,
        factoryType,
        options,
        pointerManager,
        factoryKey,
        onDeclarationFile = cacheDependencies::add,
      ) ?: return
    bindings += binding
  }

  private data class AssistedFactoryIdentity(
    val declaration: KtClassOrObject,
    val typeKey: KaTypeKey,
  )

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
          pointerManager.createSmartPsiElementPointer(ktClass),
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
              .onEach { checkCanceled() }
              .filterNot { it.hasAnyAnnotation(options.assistedAnnotations) }
              .filterNot { isCircuitProvidedType(it.returnType) }
              .map { dependencyKey(it, options) }
          addCircuitContribution(declaration, scopes, factoryClassId, dependencies)

          for (parameter in declaration.valueParameters) {
            checkCanceled()
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
            classSymbol.defaultType.allSupertypes
              .onEach { checkCanceled() }
              .mapNotNull { (it as? KaClassType)?.classId }
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
              .onEach { checkCanceled() }
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
    memberOwnerClassId: ClassId? = null,
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
      memberOwnerClassId = memberOwnerClassId,
    )
  }

  private fun KaSession.addConsumer(
    element: KtElement,
    symbol: KaCallableSymbol,
    type: KaType = symbol.returnType,
    originClassId: ClassId? = null,
    contributionScopes: Set<ClassId> = emptySet(),
    containerId: ClassId? = null,
    memberOwnerClassId: ClassId? = null,
    graphId: GraphDeclarationId? = null,
    targetConsumers: MutableList<ConsumerEntry> = consumers,
  ) {
    recordAnnotationDependencies(symbol, element)
    processRequestedAssistedFactory(type)
    targetConsumers +=
      dependencyConsumer(
        ptr(element),
        symbol,
        type,
        options,
        originClassId = originClassId,
        contributionScopes = contributionScopes,
        containerId = containerId,
        graphId = graphId,
        memberOwnerClassId = memberOwnerClassId,
      )
  }

  /** Annotation declarations own qualifier, scope, and map-key defaults used in this shard. */
  private fun KaSession.recordAnnotationDependencies(
    annotated: KaAnnotated,
    useSite: com.intellij.psi.PsiElement?,
  ) {
    val useSiteFile = useSite?.containingFile
    val metadataAnnotations =
      options.qualifierAnnotations + options.scopeAnnotations + options.mapKeyAnnotations
    for (annotation in annotated.annotations) {
      checkCanceled()
      val annotationClassId = annotation.classId ?: continue
      val annotationClass = findClass(annotationClassId) ?: continue

      // Track constants and type aliases in annotation arguments so their edits rebuild this shard.
      val argumentList = (annotation.psi as? KtAnnotationEntry)?.valueArgumentList
      if (argumentList != null) {
        PsiTreeUtil.processElements(argumentList) { element ->
          ProgressManager.checkCanceled()
          for (reference in element.references) {
            val referenced = reference.resolve()
            val isSharedDeclaration =
              referenced is KtTypeAlias ||
                (referenced is KtProperty && referenced.hasModifier(KtTokens.CONST_KEYWORD))
            if (!isSharedDeclaration) continue
            val referencedFile = referenced.containingFile
            if (referencedFile !== useSiteFile) sharedDeclarationDependencies += referencedFile
          }
          true
        }
      }

      if (annotationClass.annotations.none { it.classId in metadataAnnotations }) continue
      val declarationFile = annotationClass.psi?.containingFile ?: continue
      if (declarationFile !== useSiteFile) cacheDependencies += declarationFile
    }
    if (annotated is KaPropertySymbol) {
      val getter = annotated.getter
      if (getter != null) recordAnnotationDependencies(getter, useSite)
    }
  }
}

internal val DYNAMIC_GRAPH_CALLABLES =
  mapOf(
    CallableId(MetroClassIds.metroRuntimePackage, Name.identifier("createDynamicGraph")) to false,
    CallableId(MetroClassIds.metroRuntimePackage, Name.identifier("createDynamicGraphFactory")) to
      true,
  )
