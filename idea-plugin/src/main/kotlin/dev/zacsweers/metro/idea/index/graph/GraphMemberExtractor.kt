// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.graph

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.CallableBindingView
import dev.zacsweers.metro.idea.index.GraphInterfaceBinding
import dev.zacsweers.metro.idea.index.GraphInterfaceSurface
import dev.zacsweers.metro.idea.index.bindingData
import dev.zacsweers.metro.idea.index.bindsOptionalOfAnnotations
import dev.zacsweers.metro.idea.index.callableBindingView
import dev.zacsweers.metro.idea.index.consumedSite
import dev.zacsweers.metro.idea.index.dependencyConsumer
import dev.zacsweers.metro.idea.index.isOptionalConsumer
import dev.zacsweers.metro.idea.index.memberInjectOwners
import dev.zacsweers.metro.idea.index.memberInjectSites
import dev.zacsweers.metro.idea.index.nonAccessorCallableAnnotations
import dev.zacsweers.metro.idea.index.toKaBinding
import dev.zacsweers.metro.idea.index.typeKey
import dev.zacsweers.metro.idea.index.typeSnapshot
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphCallableReference
import dev.zacsweers.metro.idea.model.GraphCallableSignature
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphDefaultImplementation
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.model.multibindingId
import dev.zacsweers.metro.idea.qualifierAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Extracts graph members from source or compiled declarations. The caller owns the binding output
 * and dependency callbacks. Each call finishes inside its supplied Analysis API session.
 */
internal class GraphMemberExtractor(
  private val options: MetroOptions,
  private val pointerManager: SmartPointerManager,
  private val bindings: MutableList<KaBinding>,
  private val onDeclarationFile: (PsiFile) -> Unit,
  private val recordAnnotations: KaSession.(KaAnnotated, PsiElement?) -> Unit,
  private val recordRequestedType: KaSession.(KaType) -> Unit,
) {
  private val processedInheritedBindingCallables = HashSet<InheritedBindingIdentity>()
  private var cancellationWorkIndex = 0

  private fun checkCanceled() {
    checkCanceledEvery(cancellationWorkIndex++)
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  /** Records a written member and the declarations its implementation overrides. */
  fun indexDeclaredMember(
    session: KaSession,
    view: CallableBindingView,
    psi: KtElement,
    target: GraphMemberTarget,
  ) =
    with(session) {
      recordGraphDefaultImplementation(view, psi, target)
      // Binary graph declarations have no project-source annotation sweep for their providers.
      if (view.symbol.origin == KaSymbolOrigin.LIBRARY && psi is KtDeclaration) {
        val ownerDependency = target.factoryContext?.let { typeKey(it, null).canonicalContextKey() }
        processInheritedBindingCallable(psi, view, target, ownerDependency)
      }
      indexGraphCallable(view, psi, target)
    }

  /** Companion providers belong to the graph and use Kotlin's existing companion instance. */
  fun indexCompanionBindings(
    session: KaSession,
    companion: KaClassSymbol,
    target: GraphMemberTarget,
  ) =
    with(session) {
      val graphClassId = target.graphId?.classId
      // Compiler provider collection skips companions implementing the graph.
      if (graphClassId != null) {
        for (superType in companion.defaultType.allSupertypes) {
          checkCanceled()
          if ((superType as? KaClassType)?.classId == graphClassId) return@with
        }
      }
      for (callable in companion.declaredMemberScope.callables) {
        checkCanceled()
        val declaration = callable.psi as? KtDeclaration ?: continue
        declaration.containingFile?.let(onDeclarationFile)
        processInheritedBindingCallable(declaration, callableBindingView(callable), target)
      }
    }

  /** Class-literal containers contribute binary providers without adding graph accessors. */
  fun indexContainerBindings(
    session: KaSession,
    containerType: KaClassType,
    target: GraphMemberTarget,
  ) =
    with(session) {
      val scope = containerType.scope
      if (scope != null) {
        for (signature in scope.getCallableSignatures()) {
          checkCanceled()
          val view = callableBindingView(signature) ?: continue
          val declaration = view.symbol.psi as? KtDeclaration ?: continue
          declaration.containingFile?.let(onDeclarationFile)
          if (view.symbol.origin != KaSymbolOrigin.LIBRARY) continue
          processInheritedBindingCallable(declaration, view, target)
        }
      }
      val declaration = containerType.symbol.psi as? KtClassOrObject ?: return@with
      for (companion in declaration.declarations.filterIsInstance<KtObjectDeclaration>()) {
        checkCanceled()
        if (!companion.isCompanion()) continue
        val symbol = companion.symbol
        if (symbol.origin != KaSymbolOrigin.LIBRARY) continue
        indexCompanionBindings(this, symbol, target)
      }
    }

  /** Extract once; the merged index assigns owners and selects survivors for each graph path. */
  fun interfaceSurface(
    session: KaSession,
    contribution: ContributionEntry,
    classType: KaClassType,
  ): GraphInterfaceSurface =
    with(session) {
      val typeKeys = linkedSetOf<KaTypeKey>()
      val declarations = linkedSetOf<GraphReference>()
      val memberBindings = mutableListOf<GraphInterfaceBinding>()
      val memberConsumers = mutableListOf<ConsumerEntry>()
      val extensionCreations = linkedSetOf<GraphReference>()
      val extensionFactories = mutableListOf<GraphExtensionFactoryAccessor>()
      val injectedMemberOwnerIds = linkedSetOf<ClassId>()
      val target =
        GraphMemberTarget(
          null,
          memberConsumers,
          extensionCreations,
          extensionFactories,
          injectedMemberOwnerIds,
          memberBindings,
          factoryContext = classType,
        )
      for (type in sequenceOf(classType) + classType.allSupertypes) {
        checkCanceled()
        if (type.isAnyType) continue
        val superType = type as? KaClassType ?: continue
        if (!typeKeys.add(typeKey(superType, null))) continue
        declarations += superType.graphReference()
        superType.symbol.psi?.containingFile?.let(onDeclarationFile)
        indexSupertypeMembers(this, superType, target)
      }
      GraphInterfaceSurface(
        contribution,
        typeKeys,
        declarations,
        memberBindings,
        memberConsumers,
        extensionCreations,
        extensionFactories,
        injectedMemberOwnerIds,
        defaultImplementations = target.defaultImplementations,
      )
    }

  /** Indexes a graph supertype's accessors and injectors as members of the merging graph. */
  fun indexSupertypeMembers(
    session: KaSession,
    superType: KaClassType,
    target: GraphMemberTarget,
  ) =
    with(session) {
      val superClass = superType.symbol as? KaNamedClassSymbol ?: return@with
      val scope = superType.scope ?: return@with
      // The source annotation sweep never sees library files, so a library supertype's binding
      // callables index here through their decompiled declarations
      val isLibrary = superClass.origin == KaSymbolOrigin.LIBRARY
      val bindingCallableIds =
        options.providesAnnotations +
          options.bindsAnnotations +
          options.multibindsAnnotations +
          bindsOptionalOfAnnotations(options)
      for (signature in scope.getCallableSignatures()) {
        checkCanceled()
        val view = callableBindingView(signature) ?: continue
        val callable = view.symbol
        if (callable.callableId?.classId != superClass.classId) continue
        callable.psi?.containingFile?.let(onDeclarationFile)
        recordAnnotations(this, callable, callable.psi)
        val psi = callable.psi as? KtElement ?: continue
        recordGraphDefaultImplementation(view, psi, target)
        if (callable.hasAnyAnnotation(bindingCallableIds)) {
          if (target.bindingTemplates != null || isLibrary || hasSpecializedTypes(view)) {
            (callable.psi as? KtDeclaration)?.let { declaration ->
              processInheritedBindingCallable(declaration, view, target)
            }
          }
          if (!callable.hasAnyAnnotation(options.multibindsAnnotations)) {
            continue
          }
        }
        indexGraphCallable(view, psi, target)
      }
    }

  /**
   * Keep the real override relation even though a concrete member is not itself a graph request.
   * The contributing interface may be excluded later, so its implementation cannot suppress the
   * abstract declaration until the graph's path-specific contribution selection is known.
   */
  private fun KaSession.recordGraphDefaultImplementation(
    view: CallableBindingView,
    psi: KtElement,
    target: GraphMemberTarget,
  ) {
    val callable = view.symbol
    if (callable !is KaNamedFunctionSymbol && callable !is KaPropertySymbol) return
    if (view.receiver != null || callable.modality == KaSymbolModality.ABSTRACT) return

    val overriddenDeclarations = mutableListOf<GraphCallableReference>()
    val seenDeclarations = HashSet<KtElement>()
    for (overridden in callable.allOverriddenSymbols) {
      checkCanceled()
      val original = overridden.fakeOverrideOriginal
      val declaration = original.psi as? KtElement ?: continue
      if (!seenDeclarations.add(declaration)) continue
      declaration.containingFile?.let(onDeclarationFile)
      recordAnnotations(this, original, declaration)
      overriddenDeclarations += graphCallableReference(callableBindingView(original), declaration)
    }
    // Most concrete providers override nothing. They cannot satisfy another abstract declaration
    // and need no extra surface metadata or composition work.
    if (overriddenDeclarations.isEmpty()) return

    target.defaultImplementations +=
      GraphDefaultImplementation(
        declaration = graphCallableReference(view, psi),
        overriddenDeclarations = overriddenDeclarations,
        isOptional = callable.isOptionalConsumer(options),
      )
  }

  private fun KaSession.graphCallableReference(
    view: CallableBindingView,
    psi: KtElement,
  ): GraphCallableReference {
    val callable = view.symbol
    val signature =
      GraphCallableSignature(
        callableId = callable.callableId,
        receiverType = view.receiver?.let { typeSnapshot(it.returnType) },
        parameterTypes = view.valueParameters.map { typeSnapshot(it.returnType) },
        returnType = typeSnapshot(view.returnType),
        isProperty = callable is KaPropertySymbol,
        isSuspend = (callable as? KaNamedFunctionSymbol)?.isSuspend == true,
      )
    return GraphCallableReference(ptr(psi), signature)
  }

  /** The same callable classification is used for written and contributed graph supertypes. */
  private fun KaSession.indexGraphCallable(
    view: CallableBindingView,
    psi: KtElement,
    target: GraphMemberTarget,
  ) {
    val callable = view.symbol
    if (callable !is KaNamedFunctionSymbol && callable !is KaPropertySymbol) return
    if (view.receiver != null) return
    recordAnnotations(this, callable, psi)
    val isOptionalAccessor = callable.isOptionalConsumer(options)
    if (callable.modality != KaSymbolModality.ABSTRACT && !isOptionalAccessor) return
    val isMultibindingAccessor = callable.hasAnyAnnotation(options.multibindsAnnotations)
    if (
      !isMultibindingAccessor && callable.hasAnyAnnotation(nonAccessorCallableAnnotations(options))
    )
      return

    // A contributed factory's create(parameters) creates a child graph.
    val returnType = view.returnType.fullyExpandedType as? KaClassType
    val returnClass = returnType?.symbol
    if (returnType != null && returnClass != null) {
      if (returnClass.hasAnyAnnotation(options.graphExtensionAnnotations)) {
        returnClass.psi?.containingFile?.let(onDeclarationFile)
        val factoryOwner =
          graphExtensionFactoryOwner(view, target.factoryContext, options, onDeclarationFile)
        target.extensionCreations += factoryOwner ?: returnType.graphReference()
        return
      }
      if (returnClass.hasAnyAnnotation(options.graphExtensionFactoryAnnotations)) {
        val extensionType =
          graphExtensionFactoryTarget(returnType, options, onDeclarationFile) ?: return
        target.extensionCreations += returnType.graphReference()
        target.extensionFactories +=
          GraphExtensionFactoryAccessor(
            ptr(psi),
            typeKey(returnType, qualifierAnnotation(callable, options)),
            typeKey(extensionType, null),
            extensionType.graphReference(),
          )
        addGraphAccessor(view, psi, target, isOptionalAccessor)
        return
      }
    }
    if (callable is KaNamedFunctionSymbol && view.valueParameters.isNotEmpty()) {
      (psi as? KtNamedFunction)?.let {
        processGraphInjector(
          it,
          target.graphId,
          target.injectedMemberOwnerIds,
          view,
          target.consumers,
        )
      }
      return
    }
    if (view.returnType.isUnitType) return
    addGraphAccessor(view, psi, target, isOptionalAccessor)
  }

  private fun KaSession.addGraphAccessor(
    view: CallableBindingView,
    psi: KtElement,
    target: GraphMemberTarget,
    isOptional: Boolean,
  ) {
    val site = consumedSite(view.returnType, view.symbol, options)
    recordRequestedType(this, view.returnType)
    target.consumers +=
      ConsumerEntry(
        ptr(psi),
        site.contextKey,
        site.isAbstractType,
        site.multibindingId,
        site.typeClassId,
        graphId = target.graphId,
        graphRequestKind = ConsumerEntry.GraphRequestKind.ACCESSOR,
        isSuspend = (view.symbol as? KaNamedFunctionSymbol)?.isSuspend == true,
        isOptional = isOptional,
      )
  }

  /** Generic inherited providers use their concrete graph type arguments. */
  private fun KaSession.processInheritedBindingCallable(
    declaration: KtDeclaration,
    callable: CallableBindingView,
    target: GraphMemberTarget,
    ownerDependency: KaContextualTypeKey? = null,
  ) {
    recordAnnotations(this, callable.symbol, declaration)
    val graphId = target.graphId
    // The same generic base can be inherited with different arguments by unrelated graphs.
    // Owning each specialized declaration by the concrete graph prevents those bindings leaking
    // into another graph that merely shares the base class id.
    val containerId =
      graphId?.classId
        ?: (declaration as? KtCallableDeclaration)?.containingClassOrObject?.containerClassId()
    var addedBinding = false
    for (data in callable.bindingData(this, options)) {
      checkCanceled()
      val templates = target.bindingTemplates
      if (templates != null) {
        templates += GraphInterfaceBinding(ptr(declaration), data)
        addedBinding = true
        continue
      }
      val ownerGraphId = checkNotNull(graphId)
      val identity =
        InheritedBindingIdentity(
          declaration,
          ownerGraphId,
          data.key,
          data.multibindingId,
          data.mapKeyValue,
        )
      if (!processedInheritedBindingCallables.add(identity)) continue
      bindings +=
        data.toKaBinding(
          ptr(declaration),
          containerId = containerId,
          ownerGraphId = ownerGraphId,
          ownerDependency = ownerDependency,
        )
      addedBinding = true
    }
    if (!addedBinding) return
    for (parameter in callable.valueParameters) {
      checkCanceled()
      val source = parameter.symbol.psi as? KtElement ?: continue
      addConsumer(
        source,
        parameter.symbol,
        parameter.returnType,
        containerId = containerId,
        graphId = graphId,
        targetConsumers = target.consumers,
      )
    }
    val receiver = callable.receiver
    val receiverSource = (declaration as? KtCallableDeclaration)?.receiverTypeReference
    if (receiver != null && receiverSource != null) {
      addConsumer(
        receiverSource,
        receiver.symbol,
        receiver.returnType,
        containerId = containerId,
        graphId = graphId,
        targetConsumers = target.consumers,
      )
    }
  }

  /** Only create a second source binding when receiver type arguments actually change its key. */
  private fun KaSession.hasSpecializedTypes(callable: CallableBindingView): Boolean {
    val declaration = callableBindingView(callable.symbol)
    if (typeKey(callable.returnType, qualifier = null) != typeKey(declaration.returnType, null)) {
      return true
    }
    val receiver = callable.receiver
    val declaredReceiver = declaration.receiver
    if (receiver != null && declaredReceiver != null) {
      if (typeKey(receiver.returnType, null) != typeKey(declaredReceiver.returnType, null)) {
        return true
      }
    }
    return callable.valueParameters.indices.any { index ->
      val inherited = callable.valueParameters[index]
      val declared = declaration.valueParameters[index]
      typeKey(inherited.returnType, null) != typeKey(declared.returnType, null)
    }
  }

  private data class InheritedBindingIdentity(
    val declaration: KtDeclaration,
    val graphId: GraphDeclarationId,
    val typeKey: KaTypeKey,
    val multibindingId: String?,
    val mapKeyValue: String?,
  )

  /**
   * Indexes a graph injector member such as `fun inject(target: Foo)`. Each of the target's
   * member-inject keys becomes a consumer anchored at the injector.
   */
  private fun KaSession.processGraphInjector(
    member: KtNamedFunction,
    graphId: GraphDeclarationId?,
    injectedMemberOwnerIds: MutableSet<ClassId>,
    callable: CallableBindingView? = null,
    targetConsumers: MutableList<ConsumerEntry>,
  ) {
    if (member.valueParameters.size != 1) return
    val symbol = member.symbol as? KaNamedFunctionSymbol ?: return
    if (symbol.modality != KaSymbolModality.ABSTRACT) return
    val returnType = callable?.returnType ?: symbol.returnType
    if (!returnType.isUnitType) return
    if (symbol.hasAnyAnnotation(nonAccessorCallableAnnotations(options))) return
    val targetParameterType =
      callable?.valueParameters?.singleOrNull()?.returnType
        ?: symbol.valueParameters.single().returnType
    val targetType = targetParameterType.fullyExpandedType as? KaClassType ?: return
    val targetSymbol = targetType.symbol as? KaNamedClassSymbol ?: return
    for (owner in memberInjectOwners(targetSymbol, onDeclarationFile)) {
      checkCanceled()
      owner.classId?.let(injectedMemberOwnerIds::add)
    }
    for (site in
      memberInjectSites(targetType, options) { dependencyType ->
        checkCanceled()
        recordRequestedType(this, dependencyType)
      }) {
      checkCanceled()
      val contextKey = site.key
      targetConsumers +=
        ConsumerEntry(
          ptr(member),
          contextKey,
          multibindingId = contextKey.multibindingId(),
          typeClassId = contextKey.typeKey.type.classId,
          graphId = graphId,
          injectedMemberPointer = site.declaration?.let(::ptr),
          graphRequestKind = ConsumerEntry.GraphRequestKind.MEMBERS_INJECTOR,
          isOptional = contextKey.hasDefault,
        )
    }
  }

  private fun KaSession.addConsumer(
    element: KtElement,
    symbol: KaCallableSymbol,
    type: KaType,
    containerId: ClassId?,
    graphId: GraphDeclarationId?,
    targetConsumers: MutableList<ConsumerEntry>,
  ) {
    recordAnnotations(this, symbol, element)
    recordRequestedType(this, type)
    targetConsumers +=
      dependencyConsumer(
        ptr(element),
        symbol,
        type,
        options,
        containerId = containerId,
        graphId = graphId,
      )
  }
}
