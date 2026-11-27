// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeArgumentSnapshot
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.qualifierAnnotation
import dev.zacsweers.metro.idea.scopeAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol

/**
 * Returns the key a class's own binding has for [requestedKey]. It returns null when the class
 * can't satisfy the request. This mirrors the compiler's class lookup.
 *
 * A class satisfies an unqualified request even when it has a qualifier. It satisfies a qualified
 * request only when it has the same qualifier.
 *
 * An assisted factory always keeps its own qualifier. Type-level factory checks look it up under
 * that key.
 */
internal fun classBindingKey(
  requestedKey: KaTypeKey,
  classQualifier: KaAnnotationSnapshot?,
  isAssistedFactory: Boolean,
): KaTypeKey? {
  val requestedQualifier = requestedKey.qualifier
  if (requestedQualifier == classQualifier) {
    return requestedKey
  }

  if (isAssistedFactory) {
    return KaTypeKey(requestedKey.type, classQualifier)
  }

  val isQualifiedRequest = requestedQualifier != null
  if (isQualifiedRequest) {
    return null
  }

  return requestedKey
}

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
  val key = classBindingKey(requestedKey, qualifier, isFactory) ?: return null
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
  // A star has no type for the class's type parameter, matching compiler class lookup.
  val hasStarArgument = requestedKey.type.typeArguments.any { it is KaTypeArgumentSnapshot.Star }
  if (hasStarArgument) return null
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
