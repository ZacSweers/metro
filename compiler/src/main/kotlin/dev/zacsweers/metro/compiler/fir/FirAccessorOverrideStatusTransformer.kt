// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.MetroAnnotations.Kind
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.classKind
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.copy
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.hasBody
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

internal class FirAccessorOverrideStatusTransformer(session: FirSession) :
  FirStatusTransformerExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.dependencyGraphPredicate)
  }

  override fun needTransformStatus(declaration: FirDeclaration): Boolean {
    // First check if this is an accessor in a dependency graph
    if (declaration !is FirCallableDeclaration) return false
    if (declaration is FirConstructor) return false
    if (declaration is FirField) return false

    // If it's already an override, nothing needed here
    if (declaration.symbol.rawStatus.isOverride) return false

    // Only abstract callables
    if (
      declaration is FirSimpleFunction && declaration.hasBody ||
        (declaration as? FirProperty)?.getter?.hasBody == true
    ) {
      return false
    }

    if (declaration is FirSimpleFunction && declaration.valueParameters.isNotEmpty()) return false

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
    val returnType = declaration.returnTypeRef.coneTypeOrNull ?: return status

    val containingClass = declaration.getContainingClassSymbol() ?: return status

    val name = declaration.symbol.callableId?.callableName ?: return status

    var needsOverride = false
    for (superType in containingClass.getSuperTypes(session)) {
      val classSymbol = superType.toClassSymbol(session) ?: continue

      // We only want @ContributesTo types, which have supertypes
      val contributedInterface =
        classSymbol.resolvedSuperTypes.firstOrNull()?.toClassSymbol(session) ?: continue

      // Walk its direct callables. If any clash, mark needsOverride as true
      val matchingCallable =
        contributedInterface
          .callableDeclarations(session, includeSelf = true, includeAncestors = false)
          .firstOrNull {
            // Functions with params are not accessor candidates
            if (it is FirNamedFunctionSymbol && it.valueParameterSymbols.isNotEmpty())
              return@firstOrNull false
            // Extensions are not accessor candidates
            if (it.receiverParameterSymbol != null) return@firstOrNull false
            it.name == name && it.resolvedReturnType == returnType
          }

      if (matchingCallable != null) {
        needsOverride = true
        break
      }
    }

    if (!needsOverride) return status
    return status.copy(isOverride = true)
  }
}
