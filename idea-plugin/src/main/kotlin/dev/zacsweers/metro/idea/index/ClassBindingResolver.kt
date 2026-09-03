// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol

/** Captures one concrete class binding without following its dependency requests. */
internal fun KaSession.resolveClassBinding(
  symbol: KaNamedClassSymbol,
  requestedKey: KaTypeKey,
  options: MetroOptions,
  pointers: SmartPointerManager,
  onDeclarationFile: ((PsiFile) -> Unit)? = null,
): KaBinding? {
  // Nullable keys require an authored binding, matching compiler class lookup.
  if (requestedKey.type.isMarkedNullable) return null
  val declaration = symbol.psi ?: return null
  declaration.containingFile?.let { onDeclarationFile?.invoke(it) }
  val qualifier = qualifierAnnotation(symbol, options)
  val isFactory = symbol.hasAnyAnnotation(options.assistedFactoryAnnotations)
  if (qualifier != requestedKey.qualifier && !isFactory) return null
  val key =
    if (qualifier == requestedKey.qualifier) requestedKey
    else KaTypeKey(requestedKey.type, qualifier)
  val type = restoreClassType(requestedKey.type) ?: return null
  if (isFactory) {
    return assistedFactoryBinding(
      symbol,
      type,
      options,
      pointers,
      key,
      onDeclarationFile = onDeclarationFile,
    )
  }
  if (symbol.classKind == KaClassKind.OBJECT || symbol.classKind == KaClassKind.COMPANION_OBJECT) {
    return KaBinding.ConstructorInjected(
      pointers.createSmartPsiElementPointer(declaration),
      key,
      scope = null,
      implementationName = symbol.name.asString(),
      originClassId = symbol.classId,
      isObject = true,
    )
  }
  val constructor = findInjectConstructorSymbol(symbol, options) ?: return null
  val isAssisted =
    symbol.hasAnyAnnotation(options.assistedInjectAnnotations) ||
      constructor.hasAnyAnnotation(options.assistedInjectAnnotations)
  return KaBinding.ConstructorInjected(
    pointers.createSmartPsiElementPointer(declaration),
    key,
    scopeAnnotation(symbol, options),
    symbol.name.asString(),
    originClassId = symbol.classId,
    constructorDependencies = injectConstructorDependencyKeys(type, options),
    memberDependencies = memberInjectDependencyKeys(type, options, onDeclarationFile),
    memberInjectionOwnerIds = memberInjectOwnerClassIds(symbol),
    isAssisted = isAssisted,
  )
}
