// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.canonicalContextKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtObjectDeclaration

internal class GraphFactoryInputs(
  val bindingContainers: Set<KaTypeKey>,
  val graphDependencies: Set<KaTypeKey>,
  val inputs: List<FactoryInputEntry>,
  val cacheDependencies: Set<PsiFile>,
  val instanceBindings: List<KaBinding.BoundInstance> = emptyList(),
) {
  companion object {
    val EMPTY = GraphFactoryInputs(emptySet(), emptySet(), emptyList(), emptySet())
  }
}

/** Extracts the concrete inputs of a factory's semantic single abstract function. */
internal fun KaClassSymbol.graphFactoryInputs(
  session: KaSession,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  graphId: GraphDeclarationId,
  includeInstanceBindings: Boolean = false,
): GraphFactoryInputs =
  with(session) {
    val factoryScope =
      this@graphFactoryInputs.defaultType.scope ?: return@with GraphFactoryInputs.EMPTY
    val abstractFunctions =
      factoryScope
        .getCallableSignatures()
        .filterIsInstance<KaFunctionSignature<*>>()
        .filter { signature ->
          val symbol = signature.symbol
          symbol is KaNamedFunctionSymbol && symbol.modality == KaSymbolModality.ABSTRACT
        }
        .toList()
    val function = abstractFunctions.singleOrNull() ?: return@with GraphFactoryInputs.EMPTY
    val callable = callableBindingView(function) ?: return@with GraphFactoryInputs.EMPTY

    val bindingContainers = linkedSetOf<KaTypeKey>()
    val graphDependencies = linkedSetOf<KaTypeKey>()
    val inputs = mutableListOf<FactoryInputEntry>()
    val cacheDependencies = linkedSetOf<PsiFile>()
    val instanceBindings = mutableListOf<KaBinding.BoundInstance>()
    callable.symbol.psi?.containingFile?.let(cacheDependencies::add)
    for (parameter in callable.valueParameters) {
      if (includeInstanceBindings) {
        val source = parameter.symbol.psi
        if (source != null) {
          for (data in parameter.instanceBindingData(this, options)) {
            instanceBindings +=
              data.toKaBinding(
                pointerManager.createSmartPsiElementPointer(source),
                containerId = graphId.classId,
                ownerGraphId = graphId,
              ) as KaBinding.BoundInstance
          }
        }
      }
      val isIncluded = parameter.symbol.annotations.any { it.classId == MetroClassIds.includes }
      if (!isIncluded) continue
      val parameterType = parameter.returnType.fullyExpandedType as? KaClassType ?: continue
      val parameterClass = parameterType.symbol as? KaNamedClassSymbol ?: continue
      parameterClass.psi?.containingFile?.let(cacheDependencies::add)
      val parameterKey = typeKey(parameterType, qualifierAnnotation(parameter.symbol, options))

      if (parameterClass.hasAnyAnnotation(options.bindingContainerAnnotations)) {
        if (bindingContainers.add(parameterKey)) {
          inputs +=
            bindingContainerInput(
              parameterType,
              parameterKey,
              parameter.symbol.psi ?: parameterType.symbol.psi,
              options,
              pointerManager,
              cacheDependencies,
              graphId,
            )
        }
      } else if (graphDependencies.add(parameterKey)) {
        inputs +=
          includedGraphDependency(
            parameterType,
            parameterKey,
            parameter,
            options,
            pointerManager,
            cacheDependencies,
            graphId,
          )
      }
    }
    GraphFactoryInputs(
      bindingContainers,
      graphDependencies,
      inputs,
      cacheDependencies,
      instanceBindings,
    )
  }

private fun KaSession.includedGraphDependency(
  dependencyType: KaClassType,
  dependencyKey: KaTypeKey,
  parameter: CallableParameterView,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  cacheDependencies: MutableSet<PsiFile>,
  graphId: GraphDeclarationId,
): FactoryInputEntry {
  val bindings = mutableListOf<KaBinding>()
  val ownerElement = parameter.symbol.psi ?: dependencyType.symbol.psi
  if (ownerElement != null) {
    bindings +=
      KaBinding.BoundInstance(
        pointerManager.createSmartPsiElementPointer(ownerElement),
        dependencyKey,
        containerId = null,
        isGraphInput = true,
        ownerGraphId = graphId,
      )
  }

  val dependencyScope = dependencyType.scope
  if (dependencyScope != null) {
    for (signature in dependencyScope.getCallableSignatures()) {
      val callable = callableBindingView(signature) ?: continue
      val symbol = callable.symbol
      symbol.psi?.containingFile?.let(cacheDependencies::add)
      if (symbol.callableId?.classId == StandardClassIds.Any) continue
      if (symbol !is KaPropertySymbol && symbol !is KaNamedFunctionSymbol) continue
      if (symbol is KaNamedFunctionSymbol && callable.valueParameters.isNotEmpty()) continue
      if (callable.receiver != null || callable.returnType.isUnitType) continue
      val source = symbol.psi ?: continue
      bindings +=
        KaBinding.GraphDependency(
          pointerManager.createSmartPsiElementPointer(source),
          contextualTypeKey(
            callable.returnType,
            qualifierAnnotation(symbol, options),
            options,
          ),
          ownerKey = dependencyKey,
          accessorIsSuspend = (symbol as? KaNamedFunctionSymbol)?.isSuspend == true,
        )
    }
  }

  return FactoryInputEntry(
    dependencyKey,
    FactoryInputEntry.Kind.GRAPH_DEPENDENCY,
    dependencyType.symbol.psi?.containingFile?.virtualFile,
    bindings,
    emptyList(),
  )
}

internal fun KaSession.bindingContainerInput(
  containerType: KaClassType,
  containerKey: KaTypeKey,
  inputElement: PsiElement?,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  cacheDependencies: MutableSet<PsiFile>,
  graphId: GraphDeclarationId,
): FactoryInputEntry {
  val bindings = mutableListOf<KaBinding>()
  val consumers = mutableListOf<ConsumerEntry>()
  if (inputElement != null) {
    bindings +=
      KaBinding.BoundInstance(
        pointerManager.createSmartPsiElementPointer(inputElement),
        containerKey,
        containerId = null,
        isBindingContainerInput = true,
        ownerGraphId = graphId,
      )
  }
  val containerScope = containerType.scope
  if (containerScope != null) {
    for (signature in containerScope.getCallableSignatures()) {
      val callable = callableBindingView(signature) ?: continue
      addIncludedContainerCallable(
        callable,
        containerKey,
        options,
        pointerManager,
        bindings,
        consumers,
        cacheDependencies,
        requiresContainerInstance = true,
      )
    }
  }

  val containerPsi = containerType.symbol.psi as? KtClassOrObject
  val companions =
    containerPsi
      ?.declarations
      ?.filterIsInstance<KtObjectDeclaration>()
      ?.filter(KtObjectDeclaration::isCompanion)
      .orEmpty()
  for (companion in companions) {
    val companionSymbol = companion.symbol
    for (symbol in companionSymbol.declaredMemberScope.callables) {
      addIncludedContainerCallable(
        callableBindingView(symbol),
        containerKey,
        options,
        pointerManager,
        bindings,
        consumers,
        cacheDependencies,
        requiresContainerInstance = false,
      )
    }
  }

  return FactoryInputEntry(
    containerKey,
    FactoryInputEntry.Kind.BINDING_CONTAINER,
    containerType.symbol.psi?.containingFile?.virtualFile,
    bindings,
    consumers,
  )
}

private fun KaSession.addIncludedContainerCallable(
  callable: CallableBindingView,
  containerKey: KaTypeKey,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  bindings: MutableList<KaBinding>,
  consumers: MutableList<ConsumerEntry>,
  cacheDependencies: MutableSet<PsiFile>,
  requiresContainerInstance: Boolean,
) {
  val source = callable.symbol.psi ?: return
  source.containingFile?.let(cacheDependencies::add)
  val dataEntries = callable.bindingData(this, options)
  if (dataEntries.isEmpty()) return
  val pointer = pointerManager.createSmartPsiElementPointer(source)
  val ownerDependency = containerKey.canonicalContextKey().takeIf { requiresContainerInstance }
  for (data in dataEntries) {
    bindings +=
      data.toKaBinding(
        pointer,
        includedContainerKey = containerKey,
        ownerDependency = ownerDependency,
      )
  }

  val consumerOriginClassId = dataEntries.firstNotNullOfOrNull { it.originClassId }
  val contributionScopes = dataEntries.flatMapToSet { it.contributionScopes }
  val sourceElement = source as? KtElement
  val alias = dataEntries.firstOrNull { it.kind == BindingData.Kind.ALIAS }
  if (alias != null) {
    val input = callable.receiver ?: callable.valueParameters.singleOrNull() ?: return
    val consumedKey = alias.consumedKey ?: return
    val anchor = input.symbol.psi as? KtElement ?: sourceElement ?: return
    consumers +=
      ConsumerEntry(
        pointerManager.createSmartPsiElementPointer(anchor),
        consumedKey,
        originClassId = consumerOriginClassId,
        contributionScopes = contributionScopes,
        includedContainerKey = containerKey,
      )
    return
  }

  val receiver = callable.receiver
  if (receiver != null && sourceElement != null) {
    addIncludedContainerConsumer(
      receiver,
      sourceElement,
      containerKey,
      consumerOriginClassId,
      contributionScopes,
      options,
      pointerManager,
      consumers,
    )
  }
  for (parameter in callable.valueParameters) {
    if (parameter.symbol.hasAnyAnnotation(options.assistedAnnotations)) continue
    val anchor = parameter.symbol.psi as? KtElement ?: sourceElement ?: continue
    addIncludedContainerConsumer(
      parameter,
      anchor,
      containerKey,
      consumerOriginClassId,
      contributionScopes,
      options,
      pointerManager,
      consumers,
    )
  }
}

private fun KaSession.addIncludedContainerConsumer(
  parameter: CallableParameterView,
  anchor: KtElement,
  containerKey: KaTypeKey,
  originClassId: ClassId?,
  contributionScopes: Set<ClassId>,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  consumers: MutableList<ConsumerEntry>,
) {
  val site = consumedSite(parameter.returnType, parameter.symbol, options)
  consumers +=
    ConsumerEntry(
      pointerManager.createSmartPsiElementPointer(anchor),
      site.contextKey,
      site.isAbstractType,
      site.multibindingId,
      site.typeClassId,
      originClassId = originClassId,
      contributionScopes = contributionScopes,
      includedContainerKey = containerKey,
      isOptional = parameter.symbol.isOptionalConsumer(options),
    )
}
