// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.fir.checkers.AggregationChecker
import dev.zacsweers.metro.compiler.fir.checkers.AsContributionChecker
import dev.zacsweers.metro.compiler.fir.checkers.AssistedInjectChecker
import dev.zacsweers.metro.compiler.fir.checkers.BindingContainerCallableChecker
import dev.zacsweers.metro.compiler.fir.checkers.BindingContainerClassChecker
import dev.zacsweers.metro.compiler.fir.checkers.CreateGraphChecker
import dev.zacsweers.metro.compiler.fir.checkers.DependencyGraphChecker
import dev.zacsweers.metro.compiler.fir.checkers.DependencyGraphCreatorChecker
import dev.zacsweers.metro.compiler.fir.checkers.FunctionInjectionChecker
import dev.zacsweers.metro.compiler.fir.checkers.InjectConstructorChecker
import dev.zacsweers.metro.compiler.fir.checkers.InteropAnnotationChecker
import dev.zacsweers.metro.compiler.fir.checkers.MapKeyChecker
import dev.zacsweers.metro.compiler.fir.checkers.MembersInjectChecker
import dev.zacsweers.metro.compiler.fir.checkers.MergedContributionChecker
import dev.zacsweers.metro.compiler.fir.checkers.MultibindsChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirAnnotationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

internal class MetroFirCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers =
    object : DeclarationCheckers() {
      override val classCheckers: Set<FirClassChecker>
        get() =
          setOf(
            InjectConstructorChecker,
            MembersInjectChecker,
            AssistedInjectChecker,
            AggregationChecker,
            DependencyGraphCreatorChecker,
            DependencyGraphChecker,
            BindingContainerClassChecker,
            MergedContributionChecker,
            MapKeyChecker,
          )

      override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker>
        get() = setOf(BindingContainerCallableChecker, MultibindsChecker, FunctionInjectionChecker)
    }

  override val expressionCheckers: ExpressionCheckers =
    object : ExpressionCheckers() {
      override val annotationCheckers: Set<FirAnnotationChecker>
        get() {
          return if (
            session.metroFirBuiltIns.options.interopAnnotationsNamedArgSeverity.isEnabled
          ) {
            setOf(InteropAnnotationChecker)
          } else {
            super.annotationCheckers
          }
        }

      override val functionCallCheckers: Set<FirFunctionCallChecker>
        get() = setOf(CreateGraphChecker, AsContributionChecker)
    }
}
