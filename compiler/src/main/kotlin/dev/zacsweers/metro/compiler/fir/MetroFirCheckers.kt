// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.fir.checkers.AggregationChecker
import dev.zacsweers.metro.compiler.fir.checkers.AssistedInjectChecker
import dev.zacsweers.metro.compiler.fir.checkers.CreateGraphChecker
import dev.zacsweers.metro.compiler.fir.checkers.DependencyGraphChecker
import dev.zacsweers.metro.compiler.fir.checkers.DependencyGraphCreatorChecker
import dev.zacsweers.metro.compiler.fir.checkers.InjectConstructorChecker
import dev.zacsweers.metro.compiler.fir.checkers.MembersInjectChecker
import dev.zacsweers.metro.compiler.fir.checkers.MultibindsChecker
import dev.zacsweers.metro.compiler.fir.checkers.ProvidesChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
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
          )

      override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker>
        get() = setOf(ProvidesChecker, MultibindsChecker)
    }

  override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
    override val functionCallCheckers: Set<FirFunctionCallChecker>
      get() = setOf(CreateGraphChecker)
  }
}
