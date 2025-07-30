// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.isOrImplements
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.StandardClassIds

internal object MultibindsChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    val source = declaration.source ?: return
    val session = context.session

    val annotations = declaration.symbol.metroAnnotations(session)
    val isMultibinds = annotations.isMultibinds
    val isElementsIntoSet = annotations.isElementsIntoSet
    val isIntoMap = annotations.isIntoMap
    val isIntoSet = annotations.isIntoSet

    // Must check this early
    if (!annotations.isIntoMap && annotations.mapKeys.isNotEmpty()) {
      reporter.reportOn(
        annotations.mapKeys.first().fir.source ?: source,
        FirMetroErrors.MULTIBINDS_ERROR,
        "`@MapKey` annotations are only allowed on `@IntoMap` declarations.",
      )
      return
    }

    if (!isMultibinds && !isElementsIntoSet && !isIntoMap && !isIntoSet) {
      return
    }

    // Exactly one
    if (!(isMultibinds xor isElementsIntoSet xor isIntoMap xor isIntoSet)) {
      reporter.reportOn(
        source,
        FirMetroErrors.MULTIBINDS_ERROR,
        "Only one of `@Multibinds`, `@ElementsIntoSet`, `@IntoMap`, or `@IntoSet` is allowed.",
      )
      return
    }

    // @Multibinds cannot be overrides
    if (declaration.isOverride) {
      reporter.reportOn(
        source,
        FirMetroErrors.MULTIBINDS_OVERRIDE_ERROR,
        "Multibinding contributors cannot be overrides.",
      )
      return
    }

    if (annotations.isMultibinds) {
      // @Multibinds must be abstract unless private
      if (!declaration.isAbstract && declaration.visibility != Visibilities.Private) {
        reporter.reportOn(
          source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "`@Multibinds` declarations must be abstract.",
        )
        return
      }

      // Cannot also be Provides/Binds
      if (annotations.isProvides || annotations.isBinds) {
        reporter.reportOn(
          source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "`@Multibinds` declarations cannot also be annotated with `@Provides` or `@Binds` annotations.",
        )
        return
      }

      val scopeAnnotation = annotations.scope
      if (scopeAnnotation != null) {
        reporter.reportOn(
          scopeAnnotation.fir.source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "@Multibinds declarations cannot be scoped.",
        )
        return
      }

      // No need to check for explicit return types as that's enforced implicitly by the abstract
      // check above

      val returnType = declaration.returnTypeRef.coneTypeOrNull
      val returnTypeClassId = returnType?.classLikeLookupTagIfAny?.classId!!

      // @Multibinds must return only Map or Set
      if (returnTypeClassId != StandardClassIds.Map && returnTypeClassId != StandardClassIds.Set) {
        reporter.reportOn(
          declaration.returnTypeRef.source ?: source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "`@Multibinds` declarations can only return a `Map` or `Set`.",
        )
        return
      } else if (returnTypeClassId == StandardClassIds.Map) {
        when (val keyType = returnType.typeArguments[0]) {
          ConeStarProjection -> {
            reporter.reportOn(
              declaration.returnTypeRef.source ?: source,
              FirMetroErrors.MULTIBINDS_ERROR,
              "Multibinding Map keys cannot be star projections. Use a concrete type instead.",
            )
            return
          }
          else -> {
            if (keyType.type?.isMarkedNullable == true) {
              reporter.reportOn(
                declaration.returnTypeRef.source ?: source,
                FirMetroErrors.MULTIBINDS_ERROR,
                "Multibinding map keys cannot be nullable. Use a non-nullable type instead.",
              )
              return
            }
          }
        }

        val isStar =
          returnType.typeArguments[1] == ConeStarProjection ||
            returnType.typeArguments[1].type?.let {
              // Check if it's a Provider<*>
              it.typeArguments.isNotEmpty() &&
                it.classLikeLookupTagIfAny?.classId in
                  session.metroFirBuiltIns.classIds.providerTypes &&
                it.typeArguments[0] == ConeStarProjection
            } ?: false
        if (isStar) {
          reporter.reportOn(
            declaration.returnTypeRef.source ?: source,
            FirMetroErrors.MULTIBINDS_ERROR,
            "Multibinding Map values cannot be star projections. Use a concrete type instead.",
          )
          return
        }
      } else if (returnTypeClassId == StandardClassIds.Set) {
        if (returnType.typeArguments[0] == ConeStarProjection) {
          reporter.reportOn(
            declaration.returnTypeRef.source ?: source,
            FirMetroErrors.MULTIBINDS_ERROR,
            "Multibinding Set elements cannot be star projections. Use a concrete type instead.",
          )
          return
        }
      }
      return
    }

    // @IntoSet, @IntoMap, and @ElementsIntoSet must also be provides/binds
    if (!(annotations.isProvides || annotations.isBinds)) {
      reporter.reportOn(
        source,
        FirMetroErrors.MULTIBINDS_ERROR,
        "`@IntoSet`, `@IntoMap`, and `@ElementsIntoSet` must be used in conjunction with `@Provides` or `@Binds` annotations.",
      )
      return
    }

    // @ElementsIntoSet must be a Collection
    if (annotations.isElementsIntoSet) {
      // Provides checker will check separately for an explicit return type
      declaration.returnTypeRef.coneTypeOrNull?.toClassSymbol(session)?.let { returnType ->
        if (!returnType.isOrImplements(StandardClassIds.Collection, session)) {
          reporter.reportOn(
            source,
            FirMetroErrors.MULTIBINDS_ERROR,
            "`@ElementsIntoSet` must return a Collection.",
          )
          return
        }
      }
    }

    // Check for 1:1 `@IntoMap`+`@MapKey`
    if (annotations.mapKeys.size > 1) {
      for (key in annotations.mapKeys) {
        reporter.reportOn(
          key.fir.source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "Only one @MapKey should be be used on a given @IntoMap declaration.",
        )
      }
      return
    } else if (annotations.isIntoMap && annotations.mapKeys.isEmpty()) {
      reporter.reportOn(
        source,
        FirMetroErrors.MULTIBINDS_ERROR,
        "`@IntoMap` declarations must define a @MapKey annotation.",
      )
      return
    }
  }
}
