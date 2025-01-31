/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.isOrImplements
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.name.StandardClassIds

internal object MultibindsChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  override fun check(
    declaration: FirCallableDeclaration,
    context: CheckerContext,
    reporter: DiagnosticReporter,
  ) {
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
        context,
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
        context,
      )
      return
    }

    // @Multibinds cannot be overrides
    if (declaration.isOverride) {
      reporter.reportOn(
        source,
        FirMetroErrors.MULTIBINDS_OVERRIDE_ERROR,
        "Multibinding contributors cannot be overrides.",
        context,
      )
      return
    }

    if (annotations.isMultibinds) {
      // @Multibinds must be abstract
      if (!declaration.isAbstract) {
        reporter.reportOn(
          source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "`@Multibinds` declarations must be abstract.",
          context,
        )
        return
      }

      // Cannot also be Provides/Binds
      if (annotations.isProvides || annotations.isBinds) {
        reporter.reportOn(
          source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "`@Multibinds` declarations cannot also be annotated with `@Provides` or `@Binds` annotations.",
          context,
        )
        return
      }

      val scopeAnnotation = annotations.scope
      if (scopeAnnotation != null) {
        reporter.reportOn(
          scopeAnnotation.fir.source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "@Multibinds declarations cannot be scoped.",
          context,
        )
        return
      }

      // No need to check for explicit return types as that's enforced implicitly by the abstract
      // check above

      val returnType = declaration.returnTypeRef.coneTypeOrNull?.classLikeLookupTagIfAny?.classId!!

      // @Multibinds must return only Map or Set
      if (returnType != StandardClassIds.Map && returnType != StandardClassIds.Set) {
        reporter.reportOn(
          declaration.returnTypeRef.source ?: source,
          FirMetroErrors.MULTIBINDS_ERROR,
          "`@Multibinds` declarations can only return a `Map` or `Set`.",
          context,
        )
        return
      }
      return
    }

    // @IntoSet, @IntoMap, and @ElementsIntoSet must also be provides/binds
    if (!(annotations.isProvides || annotations.isBinds)) {
      reporter.reportOn(
        source,
        FirMetroErrors.MULTIBINDS_ERROR,
        "`@IntoSet`, `@IntoMap`, and `@ElementsIntoSet` must be used in conjunction with `@Provides` or `@Binds` annotations.",
        context,
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
            context,
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
          context,
        )
      }
      return
    } else if (annotations.isIntoMap && annotations.mapKeys.isEmpty()) {
      reporter.reportOn(
        source,
        FirMetroErrors.MULTIBINDS_ERROR,
        "`@IntoMap` declarations must define a @MapKey annotation.",
        context,
      )
      return
    }
  }
}
