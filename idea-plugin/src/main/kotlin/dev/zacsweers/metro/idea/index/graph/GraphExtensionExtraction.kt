// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.graph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.CallableBindingView
import dev.zacsweers.metro.idea.index.assistedFactoryFunction
import dev.zacsweers.metro.idea.index.typeKey
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaTypeKey
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType

/** Reads the child graph returned by a factory's concrete SAM. */
internal fun KaSession.graphExtensionFactoryTarget(
  factoryType: KaClassType,
  options: MetroOptions,
  onDeclarationFile: (PsiFile) -> Unit,
): KaClassType? {
  factoryType.symbol.psi?.containingFile?.let(onDeclarationFile)
  val function = assistedFactoryFunction(factoryType) ?: return null
  function.symbol.psi?.containingFile?.let(onDeclarationFile)
  val extensionType = function.returnType.fullyExpandedType as? KaClassType ?: return null
  if (!extensionType.symbol.hasAnyAnnotation(options.graphExtensionAnnotations)) return null
  extensionType.symbol.psi?.containingFile?.let(onDeclarationFile)
  return extensionType
}

/** Recognizes the factory SAM even when a graph declares its covariant override itself. */
internal fun KaSession.graphExtensionFactoryOwner(
  view: CallableBindingView,
  factoryContext: KaClassType?,
  options: MetroOptions,
  onDeclarationFile: (PsiFile) -> Unit,
): GraphReference? {
  val function = view.symbol as? KaNamedFunctionSymbol ?: return null
  val roots = mutableListOf<KaClassType>()
  if (factoryContext != null) roots += factoryContext
  val ownerId = function.callableId?.classId
  val owner = ownerId?.let { findClass(it) as? KaNamedClassSymbol }
  val ownerType = owner?.defaultType as? KaClassType
  if (ownerType != null) roots += ownerType
  val seen = hashSetOf<KaTypeKey>()
  for (root in roots) {
    ProgressManager.checkCanceled()
    for (type in sequenceOf(root) + root.allSupertypes) {
      ProgressManager.checkCanceled()
      val factoryType = type as? KaClassType ?: continue
      if (!seen.add(typeKey(factoryType, null))) continue
      if (!factoryType.symbol.hasAnyAnnotation(options.graphExtensionFactoryAnnotations)) continue
      val sam = assistedFactoryFunction(factoryType) ?: continue
      val samFunction = sam.symbol as? KaNamedFunctionSymbol ?: continue
      if (samFunction.name != function.name) continue
      if (sam.valueParameters.size != view.valueParameters.size) continue
      val sameParameters =
        sam.valueParameters.indices.all { index ->
          typeKey(sam.valueParameters[index].returnType, null) ==
            typeKey(view.valueParameters[index].returnType, null)
        }
      if (!sameParameters) continue
      factoryType.symbol.psi?.containingFile?.let(onDeclarationFile)
      return factoryType.graphReference()
    }
  }
  return null
}
