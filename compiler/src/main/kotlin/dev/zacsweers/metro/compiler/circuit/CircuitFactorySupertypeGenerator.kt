// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.compatContext
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
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId

/**
 * Supertype generator for Circuit-generated factory classes.
 *
 * This contributes `Ui.Factory` or `Presenter.Factory` as the supertype for generated factories,
 * which allows the supertype resolution to happen at the correct phase of FIR processing.
 */
internal class CircuitFactorySupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session), CompatContext by session.compatContext {

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

    val supertypeClassId = factoryType.factoryClassId

    return listOf(supertypeClassId.constructClassLikeType())
  }

  @OptIn(SymbolInternals::class)
  private fun determineFactoryType(
    declaration: FirClass,
    typeResolver: TypeResolveService,
  ): FactoryType? {
    // Happy path for top-level factories
    declaration.symbol.origin
      .expectAsOrNull<FirDeclarationOrigin.Plugin>()
      ?.key
      ?.expectAsOrNull<CircuitOrigins.FactoryClass>()
      ?.type
      ?.let {
        return it
      }

    val parent =
      declaration.getContainingClassSymbol()?.expectAs<FirClassSymbol<FirClass>>() ?: return null
    val queue = ArrayDeque<FirClass>()
    val seen = mutableSetOf<ClassId>()
    queue.add(parent.fir)
    for (clazz in queue) {
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
        if (coneType.classId in seen) continue
        when (classId) {
          FactoryType.PRESENTER.classId -> return FactoryType.PRESENTER
          FactoryType.UI.classId -> return FactoryType.UI
          else -> {
            // Unrecognized, add the class to our queue
            coneType.toClassSymbol(session)?.let { queue.add(it.fir) }
          }
        }
      }
    }

    return null
  }
}
