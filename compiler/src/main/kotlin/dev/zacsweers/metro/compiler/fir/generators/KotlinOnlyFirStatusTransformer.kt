// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirBackingField
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

/**
 * FIR extensions run on Java sources too (!!), so we decorate ours with this to only operate on
 * Kotlin sources.
 */
internal class KotlinOnlyFirStatusTransformerExtension(
  session: FirSession,
  private val delegate: FirStatusTransformerExtension,
) : FirStatusTransformerExtension(session) {
  override fun needTransformStatus(declaration: FirDeclaration): Boolean {
    return if (declaration.origin is FirDeclarationOrigin.Java.Source) {
      false
    } else {
      delegate.needTransformStatus(declaration)
    }
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    property: FirProperty,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, property, containingClass, isLocal)
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    function: FirSimpleFunction,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, function, containingClass, isLocal)
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    regularClass: FirRegularClass,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, regularClass, containingClass, isLocal)
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    typeAlias: FirTypeAlias,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, typeAlias, containingClass, isLocal)
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    propertyAccessor: FirPropertyAccessor,
    containingClass: FirClassLikeSymbol<*>?,
    containingProperty: FirProperty?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(
      status,
      propertyAccessor,
      containingClass,
      containingProperty,
      isLocal,
    )
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    constructor: FirConstructor,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, constructor, containingClass, isLocal)
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    field: FirField,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, field, containingClass, isLocal)
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    backingField: FirBackingField,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, backingField, containingClass, isLocal)
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    enumEntry: FirEnumEntry,
    containingClass: FirClassLikeSymbol<*>?,
    isLocal: Boolean,
  ): FirDeclarationStatus {
    return delegate.transformStatus(status, enumEntry, containingClass, isLocal)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    val registrar = this
    with(delegate) { registrar.registerPredicates() }
  }
}

internal fun FirStatusTransformerExtension.kotlinOnly() =
  KotlinOnlyFirStatusTransformerExtension(session, this)
