// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * FIR extensions run on Java sources too (!!), so we decorate ours with this to only operate on
 * Kotlin sources.
 */
internal class KotlinOnlyFirGenerationExtension(
  session: FirSession,
  private val delegate: FirDeclarationGenerationExtension,
) : FirDeclarationGenerationExtension(session) {
  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    return delegate.generateConstructors(context)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    return delegate.generateFunctions(callableId, context)
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return delegate.generateNestedClassLikeDeclaration(owner, name, context)
  }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> {
    return delegate.generateProperties(callableId, context)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    return delegate.generateTopLevelClassLikeDeclaration(classId)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> {
    return delegate.getTopLevelCallableIds()
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    return delegate.getTopLevelClassIds()
  }

  override fun hasPackage(packageFqName: FqName): Boolean {
    return delegate.hasPackage(packageFqName)
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    val registrar = this
    with(delegate) { registrar.registerPredicates() }
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (classSymbol.origin is FirDeclarationOrigin.Java.Source) return emptySet()
    return delegate.getCallableNamesForClass(classSymbol, context)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (classSymbol.origin is FirDeclarationOrigin.Java.Source) return emptySet()
    return delegate.getNestedClassifiersNames(classSymbol, context)
  }
}

internal fun FirDeclarationGenerationExtension.kotlinOnly() =
  KotlinOnlyFirGenerationExtension(session, this)
