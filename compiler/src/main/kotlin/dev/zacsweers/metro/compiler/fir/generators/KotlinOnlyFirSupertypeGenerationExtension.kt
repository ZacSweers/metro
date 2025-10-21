// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef

/**
 * FIR extensions run on Java sources too (!!), so we decorate ours with this to only operate on
 * Kotlin sources.
 */
internal class KotlinOnlyFirSupertypeGenerationExtension(
  session: FirSession,
  private val delegate: FirSupertypeGenerationExtension,
) : FirSupertypeGenerationExtension(session) {
  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    return delegate.computeAdditionalSupertypesForGeneratedNestedClass(klass, typeResolver)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    val registrar = this
    with(delegate) { registrar.registerPredicates() }
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    return if (declaration.symbol.origin is FirDeclarationOrigin.Java.Source) {
      false
    } else {
      delegate.needTransformSupertypes(declaration)
    }
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    return delegate.computeAdditionalSupertypes(
      classLikeDeclaration,
      resolvedSupertypes,
      typeResolver,
    )
  }
}

internal fun FirSupertypeGenerationExtension.kotlinOnly() =
  KotlinOnlyFirSupertypeGenerationExtension(session, this)
