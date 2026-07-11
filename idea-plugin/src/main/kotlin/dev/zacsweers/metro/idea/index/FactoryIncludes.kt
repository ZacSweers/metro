// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
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

private val INCLUDES_ANNOTATIONS = setOf(MetroClassIds.includes)

internal class FactoryIncludes(
  val bindingContainers: Set<KaTypeKey>,
  val graphDependencies: Set<KaTypeKey>,
  val inputs: List<FactoryInputEntry>,
  val cacheDependencies: Set<PsiFile>,
) {
  companion object {
    val EMPTY = FactoryIncludes(emptySet(), emptySet(), emptyList(), emptySet())
  }
}

/** Extracts the concrete inputs of a factory's semantic single abstract function. */
internal fun KaSession.factoryIncludes(
  factory: KaClassSymbol,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
): FactoryIncludes {
  val factoryScope = factory.defaultType.scope ?: return FactoryIncludes.EMPTY
  val abstractFunctions =
    factoryScope
      .getCallableSignatures()
      .filterIsInstance<KaFunctionSignature<*>>()
      .filter { signature ->
        val symbol = signature.symbol
        symbol is KaNamedFunctionSymbol && symbol.modality == KaSymbolModality.ABSTRACT
      }
      .toList()
  val function = abstractFunctions.singleOrNull() ?: return FactoryIncludes.EMPTY
  val callable = callableBindingView(function) ?: return FactoryIncludes.EMPTY

  val bindingContainers = linkedSetOf<KaTypeKey>()
  val graphDependencies = linkedSetOf<KaTypeKey>()
  val inputs = mutableListOf<FactoryInputEntry>()
  val cacheDependencies = linkedSetOf<PsiFile>()
  callable.symbol.psi?.containingFile?.let(cacheDependencies::add)
  for (parameter in callable.valueParameters) {
    if (!parameter.symbol.hasAnyAnnotation(INCLUDES_ANNOTATIONS)) continue
    val parameterType = parameter.returnType.fullyExpandedType as? KaClassType ?: continue
    val parameterClass = parameterType.symbol as? KaNamedClassSymbol ?: continue
    parameterClass.psi?.containingFile?.let(cacheDependencies::add)
    val parameterKey = typeKey(parameterType, qualifierAnnotation(parameter.symbol, options))

    if (parameterClass.hasAnyAnnotation(options.bindingContainerAnnotations)) {
      if (bindingContainers.add(parameterKey)) {
        inputs +=
          includedBindingContainer(
            parameterType,
            parameterKey,
            options,
            pointerManager,
            cacheDependencies,
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
        )
    }
  }
  return FactoryIncludes(bindingContainers, graphDependencies, inputs, cacheDependencies)
}

private fun KaSession.includedGraphDependency(
  dependencyType: KaClassType,
  dependencyKey: KaTypeKey,
  parameter: CallableParameterView,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  cacheDependencies: MutableSet<PsiFile>,
): FactoryInputEntry {
  val bindings = mutableListOf<KaBinding>()
  val ownerElement = dependencyType.symbol.psi ?: parameter.symbol.psi
  if (ownerElement != null) {
    bindings +=
      KaBinding.BoundInstance(
        pointerManager.createSmartPsiElementPointer(ownerElement),
        dependencyKey,
        containerId = null,
        isGraphInput = true,
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
        )
    }
  }

  return FactoryInputEntry(
    dependencyKey,
    FactoryInputEntry.Kind.GRAPH_DEPENDENCY,
    bindings,
    emptyList(),
  )
}

private fun KaSession.includedBindingContainer(
  containerType: KaClassType,
  containerKey: KaTypeKey,
  options: MetroOptions,
  pointerManager: SmartPointerManager,
  cacheDependencies: MutableSet<PsiFile>,
): FactoryInputEntry {
  val bindings = mutableListOf<KaBinding>()
  val consumers = mutableListOf<ConsumerEntry>()
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
      )
    }
  }

  return FactoryInputEntry(
    containerKey,
    FactoryInputEntry.Kind.BINDING_CONTAINER,
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
) {
  val source = callable.symbol.psi ?: return
  source.containingFile?.let(cacheDependencies::add)
  val dataEntries = bindingData(callable, options)
  if (dataEntries.isEmpty()) return
  val pointer = pointerManager.createSmartPsiElementPointer(source)
  for (data in dataEntries) {
    bindings += data.toKaBinding(pointer, includedContainerKey = containerKey)
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
