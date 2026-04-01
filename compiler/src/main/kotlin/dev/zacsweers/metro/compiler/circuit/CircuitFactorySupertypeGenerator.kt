// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId

/**
 * Supertype generator for Circuit-generated factory classes.
 *
 * This contributes `Ui.Factory` or `Presenter.Factory` as the supertype for generated factories,
 * which allows the supertype resolution to happen at the correct phase of FIR processing.
 *
 * For top-level function factories, their supertype is added when the class is created and do not
 * appear to pass through this API. For nested class factories, the factory type is determined via
 * BFS through the parent class's supertypes.
 */
internal class CircuitFactorySupertypeGenerator(session: FirSession, compatContext: CompatContext) :
  FirSupertypeGenerationExtension(session), CompatContext by compatContext {

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(CircuitSymbols.circuitInjectPredicate)
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    return declaration.symbol.origin.expectAsOrNull<FirDeclarationOrigin.Plugin>()?.key is
      CircuitOrigins.FactoryClass
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    // For top-level factory classes
    return computeFactorySupertype(classLikeDeclaration as FirClass, typeResolver)
  }

  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    // For nested factory classes
    return computeFactorySupertype(klass, typeResolver)
  }

  private fun computeFactorySupertype(
    declaration: FirClass,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    // Determine factory type from parent class (for nested) or from the factory name (for
    // top-level)
    val factoryType = determineFactoryType(declaration, typeResolver) ?: return emptyList()
    return listOf(factoryType.factoryClassId.constructClassLikeType())
  }

  @OptIn(SymbolInternals::class)
  private fun determineFactoryType(
    declaration: FirClass,
    typeResolver: TypeResolveService,
  ): FactoryType? {
    // Happy path: factoryType is stored in the origin key. This handles:
    // - Top-level function factories (set during class creation)
    // - Assisted factory classes (resolved from containing class in CircuitFirExtension)
    declaration.symbol.origin
      .expectAsOrNull<FirDeclarationOrigin.Plugin>()
      ?.key
      ?.expectAsOrNull<CircuitOrigins.FactoryClass>()
      ?.type
      ?.let {
        return it
      }

    // For nested factories, BFS through the parent class's supertypes
    val parent =
      declaration.getContainingClassSymbol()?.expectAs<FirClassSymbol<FirClass>>() ?: return null
    bfsForFactoryType(parent.fir, typeResolver)?.let {
      return it
    }

    // If parent BFS didn't find Presenter/Ui, the parent may be an @AssistedFactory
    // nested inside the actual Presenter/Ui class. Try the grandparent.
    val grandparent = parent.getContainingClassSymbol()?.expectAs<FirClassSymbol<FirClass>>()
    if (grandparent != null) {
      return bfsForFactoryType(grandparent.fir, typeResolver)
    }

    return null
  }

  /** BFS through [root]'s supertypes looking for [FactoryType.PRESENTER] or [FactoryType.UI]. */
  @OptIn(SymbolInternals::class)
  private fun bfsForFactoryType(root: FirClass, typeResolver: TypeResolveService): FactoryType? {
    val queue = ArrayDeque<FirClass>()
    val seen = mutableSetOf<ClassId>()
    queue.add(root)
    while (queue.isNotEmpty()) {
      val clazz = queue.removeFirst()
      if (clazz.classId in seen) continue
      seen += clazz.classId

      for (supertypeRef in clazz.superTypeRefs) {
        val supertype =
          when (supertypeRef) {
            is FirUserTypeRef -> typeResolver.resolveUserType(supertypeRef)
            is FirResolvedTypeRef -> supertypeRef
            else -> continue
          }
        val coneType = supertype.coneType
        val classId = coneType.classId ?: continue
        if (classId in seen) continue
        when (classId) {
          FactoryType.PRESENTER.classId -> return FactoryType.PRESENTER
          FactoryType.UI.classId -> return FactoryType.UI
          else -> coneType.toClassSymbol(session)?.let { queue.add(it.fir) }
        }
      }
    }
    return null
  }
}
