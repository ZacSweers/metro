/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.fir

import java.util.Objects
import kotlin.collections.contains
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.superConeTypes
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.scopes.jvm.computeJvmDescriptor
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.ClassId

internal fun FirAnnotationContainer.isAnnotatedWithAny(
  session: FirSession,
  names: Collection<ClassId>,
): Boolean {
  return names.any { hasAnnotation(it, session) }
}

internal fun FirAnnotationContainer.annotationsIn(
  session: FirSession,
  names: Set<ClassId>,
): Sequence<FirAnnotation> {
  return annotations.annotationsIn(session, names)
}

internal fun List<FirAnnotation>.annotationsIn(
  session: FirSession,
  names: Set<ClassId>,
): Sequence<FirAnnotation> {
  return asSequence().filter { it.toAnnotationClassId(session) in names }
}

internal fun List<FirAnnotation>.isAnnotatedWithAny(
  session: FirSession,
  names: Set<ClassId>,
): Boolean {
  return annotationsIn(session, names).any()
}

internal inline fun FirMemberDeclaration.checkVisibility(
  onError: (source: KtSourceElement?) -> Nothing
) {
  visibility.checkVisibility(source, onError)
}

internal inline fun FirCallableSymbol<*>.checkVisibility(
  onError: (source: KtSourceElement?) -> Nothing
) {
  visibility.checkVisibility(source, onError)
}

internal inline fun Visibility.checkVisibility(
  source: KtSourceElement?,
  onError: (source: KtSourceElement?) -> Nothing,
) {
  // TODO what about expect/actual/protected
  when (this) {
    Visibilities.Public,
    Visibilities.Internal -> {
      // These are fine
      // TODO what about across modules? Is internal really ok? Or PublishedApi?
    }
    else -> {
      onError(source)
    }
  }
}

@OptIn(SymbolInternals::class) // TODO is there a non-internal API?
internal fun FirClass.allSuperTypeConeRefs(session: FirSession): Sequence<ConeClassLikeType> {
  return sequence {
    yieldAll(superConeTypes)
    for (supertype in superConeTypes) {
      val clazz = supertype.toClassSymbol(session)
      clazz?.resolvedSuperTypeRefs?.mapNotNull { it.coneTypeSafe() }
    }
  }
}

@OptIn(SymbolInternals::class) // TODO is there a non-internal API?
internal fun FirClass.allFunctions(session: FirSession): Sequence<FirFunction> {
  return sequence {
    yieldAll(declarations.filterIsInstance<FirFunction>())
    yieldAll(
      allSuperTypeConeRefs(session)
        .mapNotNull { it.toClassSymbol(session)?.fir }
        .flatMap { it.allFunctions(session) }
    )
  }
}

internal fun FirClass.abstractFunctions(session: FirSession): List<FirFunction> {
  return allFunctions(session)
    // Merge inherited functions with matching signatures
    .groupBy {
      // Don't include the return type because overrides may have different ones
      it.computeJvmDescriptor(includeReturnType = false)
    }
    .mapValues { (_, functions) ->
      val (abstract, implemented) =
        functions.partition {
          it.modality == Modality.ABSTRACT &&
            it.body == null &&
            (it.visibility == Visibilities.Public || it.visibility == Visibilities.Protected)
        }
      if (abstract.isEmpty()) {
        // All implemented, nothing to do
        null
      } else if (implemented.isNotEmpty()) {
        // If there's one implemented one, it's not abstract anymore in our materialized type
        null
      } else {
        // Only need one for the rest of this
        abstract[0]
      }
    }
    .values
    .filterNotNull()
}

/**
 * Computes a hash key for this annotation instance composed of its underlying type and value
 * arguments.
 */
internal fun FirAnnotationCall.computeAnnotationHash(): Int {
  return Objects.hash(
    resolvedType.classId,
    arguments.map { (it as FirLiteralExpression).value }.toTypedArray().contentDeepHashCode(),
  )
}
