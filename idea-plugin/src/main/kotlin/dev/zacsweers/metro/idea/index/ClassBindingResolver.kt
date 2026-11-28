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
 * Returns the key of the binding that a class with [classQualifier] gives a request for
 * [requestedKey].
 *
 * A class resolves a request that uses its qualifier. It also resolves a request with no qualifier.
 * Both keep the requested key, so each key gets its own binding like the compiler's class lookup. A
 * request with a different qualifier gets the class's own key, which doesn't satisfy that request.
 */
internal fun classBindingKey(
  requestedKey: KaTypeKey,
  classQualifier: KaAnnotationSnapshot?,
): KaTypeKey {
  val requestQualifier = requestedKey.qualifier
  val hasNoQualifier = requestQualifier == null
  val usesClassQualifier = requestQualifier == classQualifier
  if (hasNoQualifier || usesClassQualifier) {
    return requestedKey
  }

  return KaTypeKey(requestedKey.type, classQualifier)
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
  val key = classBindingKey(requestedKey, qualifier)

  // Factory checks find a factory by its type, so it's indexed even when it can't satisfy the
  // request.
  val satisfiesRequest = key == requestedKey
  val isFactory = symbol.hasAnyAnnotation(options.assistedFactoryAnnotations)
  if (!satisfiesRequest && !isFactory) {
    return null
  }

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
      classQualifier = qualifier,
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
    classQualifier = qualifier,
  )
}
