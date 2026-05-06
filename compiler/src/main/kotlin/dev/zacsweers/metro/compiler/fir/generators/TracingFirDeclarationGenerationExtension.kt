// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.tracing.TraceCategories
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Wraps a [FirDeclarationGenerationExtension] so each call to a productive `generate*` method opens
 * a [TraceCategories.FIR_GEN] trace span tagged with [tag]. The high-frequency name-enumeration
 * methods (`get*Names`, `getTop*Ids`, `hasPackage`) are passed through untraced — they fire many
 * times per session during resolution and would dominate the trace with little useful information.
 *
 * `session.trace(...)` is a fast no-op when tracing is disabled, so this wrapper has no measurable
 * cost off the trace path.
 */
internal class TracingFirDeclarationGenerationExtension(
  session: FirSession,
  private val tag: String,
  private val delegate: FirDeclarationGenerationExtension,
) : FirDeclarationGenerationExtension(session) {

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> =
    session.trace(
      name = { "$tag.generateConstructors(${context.owner.classId})" },
      category = TraceCategories.FIR_GEN,
    ) {
      delegate.generateConstructors(context)
    }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> =
    session.trace(
      name = { "$tag.generateFunctions($callableId)" },
      category = TraceCategories.FIR_GEN,
    ) {
      delegate.generateFunctions(callableId, context)
    }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> =
    session.trace(
      name = { "$tag.generateProperties($callableId)" },
      category = TraceCategories.FIR_GEN,
    ) {
      delegate.generateProperties(callableId, context)
    }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? =
    session.trace(
      name = { "$tag.generateNestedClassLikeDeclaration(${owner.classId}, $name)" },
      category = TraceCategories.FIR_GEN,
    ) {
      delegate.generateNestedClassLikeDeclaration(owner, name, context)
    }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? =
    session.trace(
      name = { "$tag.generateTopLevelClassLikeDeclaration($classId)" },
      category = TraceCategories.FIR_GEN,
    ) {
      delegate.generateTopLevelClassLikeDeclaration(classId)
    }

  // Untraced pass-throughs — too high-frequency to be useful in a trace.
  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> = delegate.getCallableNamesForClass(classSymbol, context)

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> = delegate.getNestedClassifiersNames(classSymbol, context)

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> = delegate.getTopLevelCallableIds()

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> = delegate.getTopLevelClassIds()

  override fun hasPackage(packageFqName: FqName): Boolean = delegate.hasPackage(packageFqName)

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    val registrar = this
    with(delegate) { registrar.registerPredicates() }
  }
}

/**
 * Supertype-generator equivalent of [TracingFirDeclarationGenerationExtension]. Traces the two
 * `compute*` methods under [TraceCategories.FIR_SUPERTYPE]; `needTransformSupertypes` is left
 * untraced because it is a cheap predicate that fires on many declarations.
 */
internal class TracingFirSupertypeGenerationExtension(
  session: FirSession,
  private val tag: String,
  private val delegate: FirSupertypeGenerationExtension,
) : FirSupertypeGenerationExtension(session) {

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean =
    delegate.needTransformSupertypes(declaration)

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> =
    session.trace(
      name = { "$tag.computeAdditionalSupertypes(${classLikeDeclaration.classId})" },
      category = TraceCategories.FIR_SUPERTYPE,
    ) {
      delegate.computeAdditionalSupertypes(classLikeDeclaration, resolvedSupertypes, typeResolver)
    }

  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> =
    session.trace(
      name = { "$tag.computeAdditionalSupertypesForGeneratedNestedClass(${klass.classId})" },
      category = TraceCategories.FIR_SUPERTYPE,
    ) {
      delegate.computeAdditionalSupertypesForGeneratedNestedClass(klass, typeResolver)
    }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    val registrar = this
    with(delegate) { registrar.registerPredicates() }
  }
}
