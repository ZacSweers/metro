// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.classArgument
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

private val contributesIntoSetClassId =
  ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("ContributesIntoSet"))

internal fun FirSession.buildCircuitInjectAnnotation(): FirAnnotation {
  return buildSimpleAnnotation { metroFirBuiltIns.injectClassSymbol }
}

internal fun FirSession.buildCircuitContributesIntoSetAnnotation(
  scopeClassId: ClassId
): FirAnnotation {
  val contributesIntoSetSymbol =
    symbolProvider.getClassLikeSymbolByClassId(contributesIntoSetClassId) as? FirRegularClassSymbol
      ?: error("Could not find ContributesIntoSet")
  val scopeSymbol =
    symbolProvider.getClassLikeSymbolByClassId(scopeClassId)
      ?: error("Could not find scope class: $scopeClassId")
  val scopeType = (scopeSymbol as FirRegularClassSymbol).defaultType()

  return buildAnnotation {
    annotationTypeRef = contributesIntoSetSymbol.defaultType().toFirResolvedTypeRef()
    argumentMapping = buildAnnotationArgumentMapping {
      mapping[CircuitNames.scope] = buildGetClassCall {
        argumentList = buildArgumentList {
          arguments +=
            with(this@buildCircuitContributesIntoSetAnnotation.compatContext) {
              buildResolvedQualifierCompat(scopeClassId, scopeSymbol, scopeType)
            }
        }
        coneTypeOrNull =
          ConeClassLikeTypeImpl(
            StandardClassIds.KClass.toLookupTag(),
            arrayOf(scopeType),
            isMarkedNullable = false,
          )
      }
    }
  }
}

internal fun FirAnnotation.extractCircuitScopeClassId(
  session: FirSession,
  typeResolver: MetroFirTypeResolver,
  argumentIndex: Int,
): ClassId? {
  if (this !is FirAnnotationCall) return null
  return classArgument(session, Symbols.Names.scope, index = argumentIndex)
    ?.resolveClassId(typeResolver)
}
