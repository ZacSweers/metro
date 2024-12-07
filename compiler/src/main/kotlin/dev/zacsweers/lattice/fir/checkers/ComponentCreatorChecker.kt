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
package dev.zacsweers.lattice.fir.checkers

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.fir.FirLatticeErrors
import dev.zacsweers.lattice.fir.FirTypeKey
import dev.zacsweers.lattice.fir.LatticeFirAnnotation
import dev.zacsweers.lattice.fir.annotationsIn
import dev.zacsweers.lattice.fir.checkVisibility
import dev.zacsweers.lattice.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.fir.singleAbstractFunction
import dev.zacsweers.lattice.fir.validateFactoryClass
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.resolvedType

internal class ComponentCreatorChecker(
  private val session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    val source = declaration.source ?: return
    val componentFactoryAnnotation =
      declaration.annotationsIn(session, latticeClassIds.componentFactoryAnnotations).toList()

    if (componentFactoryAnnotation.isEmpty()) return

    declaration.validateFactoryClass(context, reporter, "Component factory") { return }

    val createFunction = declaration.singleAbstractFunction(session, context, reporter, "@Component.Factory") { return }

    val paramTypes = mutableSetOf<FirTypeKey>()

    for (param in createFunction.valueParameters) {
      val clazz = param.returnTypeRef.firClassLike(session)!!
      val isValid =
        param.isAnnotatedWithAny(session, latticeClassIds.bindsInstanceAnnotations) ||
          clazz.isAnnotatedWithAny(session, latticeClassIds.componentAnnotations)
      if (!isValid) {
        reporter.reportOn(
          param.source,
          FirLatticeErrors.COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_BINDSINSTANCE_OR_COMPONENTS,
          context,
        )
        return
      }
      // Check duplicate params
      val qualifier =
        param.annotations
          .filterIsInstance<FirAnnotationCall>()
          .singleOrNull { annotationCall ->
            val annotationType =
              annotationCall.resolvedType as? ConeClassLikeType ?: return@singleOrNull false
            val annotationClass = annotationType.toClassSymbol(session) ?: return@singleOrNull false
            annotationClass.annotations.isAnnotatedWithAny(
              session,
              latticeClassIds.qualifierAnnotations,
            )
          }
          ?.let { LatticeFirAnnotation(it) }
      val typeKey = FirTypeKey(param.returnTypeRef, qualifier)
      if (!paramTypes.add(typeKey)) {
        reporter.reportOn(
          param.source,
          FirLatticeErrors.COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_UNIQUE,
          context,
        )
        return
      }
    }
  }
}
