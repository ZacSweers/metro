// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.MetroAnnotations.Kind
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.classKind
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirBackingField
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirErrorFunction
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.hasBody
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

internal class FirAccessorOverrideStatusTransformer(session: FirSession) :
  FirStatusTransformerExtension(session), CompatContext by session.compatContext {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.dependencyGraphPredicate)
  }

  override fun needTransformStatus(declaration: FirDeclaration): Boolean {
    // First check if this is an accessor in a dependency graph
    if (declaration !is FirCallableDeclaration) return false

    // Skip Java sources
    if (declaration.origin is FirDeclarationOrigin.Java.Source) return false

    when (declaration) {
      is FirAnonymousFunction,
      is FirConstructor,
      is FirErrorFunction,
      is FirPropertyAccessor,
      is FirBackingField,
      is FirEnumEntry,
      is FirField,
      is FirValueParameter -> return false
      is FirFunction, // FirSimpleFunction/FirNamedFunction
      is FirProperty -> {
        // Continue on
      }
    }

    // If it's already an override, nothing needed here
    if (declaration.symbol.rawStatus.isOverride) return false

    // Only abstract callables
    if (
      declaration is FirFunction && declaration.hasBody ||
        (declaration as? FirProperty)?.getter?.hasBody == true
    ) {
      return false
    }

    if (declaration is FirFunction && declaration.valueParameters.isNotEmpty()) return false

    val containingClass = declaration.getContainingClassSymbol() ?: return false
    val isInGraph =
      session.predicateBasedProvider.matches(
        session.predicates.dependencyGraphPredicate,
        containingClass,
      )

    if (!isInGraph) return false

    // isAbstract doesn't work in interfaces
    if (containingClass.classKind?.isInterface != true && !declaration.isAbstract) return false

    // Exclude Provides/Binds
    val annotations = declaration.symbol.metroAnnotations(session, Kind.Provides, Kind.Binds)
    val isAccessor = !annotations.isProvides && !annotations.isBinds

    if (!isAccessor) return false

    return true
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    declaration: FirDeclaration,
  ): FirDeclarationStatus {
    if (declaration !is FirCallableDeclaration) return status
    declaration.returnTypeRef.coneTypeOrNull ?: return status
    declaration.symbol.callableId?.callableName ?: return status
    val containingClass = declaration.getContainingClassSymbol() ?: return status

    // Track checked types to avoid duplicate checks
    val checkedSuperTypes = mutableSetOf<ConeKotlinType>()

    for (superType in containingClass.getSuperTypes(session)) {
      // Skip if we've already checked this super type
      if (!checkedSuperTypes.add(superType)) continue

      val classSymbol = superType.toClassSymbol(session) ?: continue

      // We only want @ContributesTo types, which have supertypes
      val contributedInterface =
        classSymbol.resolvedSuperTypes.firstOrNull()?.toClassSymbol(session) ?: continue

      // Walk its direct callables. If any clash, mark needsOverride as true
      val hasMatchingCallable =
        contributedInterface
          .callableDeclarations(session, includeSelf = true, includeAncestors = false)
          .any { callable ->
            // Extensions are not accessor candidates
            callable.receiverParameterSymbol == null &&
              // Functions with params are not accessor candidates
              (callable !is FirNamedFunctionSymbol || callable.valueParameterSymbols.isEmpty()) &&
              callable.matchesSignatureOf(declaration.symbol)
          }

      if (hasMatchingCallable) {
        // Found a match - immediately return transformed status
        return status.copy(isOverride = true)
      }
    }

    return status
  }

  fun FirCallableSymbol<*>.matchesSignatureOf(other: FirCallableSymbol<*>): Boolean {
    val thisIsFunction = this is FirFunctionSymbol
    val otherIsFunction = other is FirFunctionSymbol
    if (thisIsFunction != otherIsFunction) return false
    return name == other.name
  }
}
