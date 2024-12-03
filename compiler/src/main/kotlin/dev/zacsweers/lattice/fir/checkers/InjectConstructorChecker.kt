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
import dev.zacsweers.lattice.fir.annotationsIn
import dev.zacsweers.lattice.fir.checkVisibility
import dev.zacsweers.lattice.fir.isAnnotatedWithAny
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.constructors
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.modality

internal class InjectConstructorChecker(
  private val session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    val source = declaration.source ?: return
    val classInjectAnnotation =
      declaration.annotationsIn(session, latticeClassIds.injectAnnotations).toList()

    val constructorInjections =
      declaration.constructors(session).filter {
        it.annotations.isAnnotatedWithAny(session, latticeClassIds.injectAnnotations)
      }

    val isInjected = classInjectAnnotation.isNotEmpty() || constructorInjections.isNotEmpty()
    if (!isInjected) return

    if (constructorInjections.size > 1) {
      reporter.reportOn(
        constructorInjections[0]
          .annotations
          .annotationsIn(session, latticeClassIds.injectAnnotations)
          .single()
          .source,
        FirLatticeErrors.CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS,
        context,
      )
      return
    }

    if (classInjectAnnotation.isNotEmpty() && constructorInjections.isNotEmpty()) {
      reporter.reportOn(
        classInjectAnnotation[0].source,
        FirLatticeErrors.CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
        context,
      )
      return
    }

    if (constructorInjections.isNotEmpty()) {
      val constructor = constructorInjections.single()
      if (constructor.valueParameterSymbols.isEmpty()) {
        reporter.reportOn(
          constructorInjections[0]
            .annotations
            .annotationsIn(session, latticeClassIds.injectAnnotations)
            .single()
            .source,
          FirLatticeErrors.SUGGEST_CLASS_INJECTION_IF_NO_PARAMS,
          context,
        )
      }
    }

    if (declaration.isLocal) {
      reporter.reportOn(source, FirLatticeErrors.LOCAL_CLASSES_CANNOT_BE_INJECTED, context)
      return
    }

    when (declaration.classKind) {
      ClassKind.CLASS -> {
        when (declaration.modality) {
          Modality.FINAL -> {
            // This is fine
          }
          else -> {
            // open/sealed/abstract
            reporter.reportOn(source, FirLatticeErrors.ONLY_CLASSES_CAN_BE_INJECTED, context)
            return
          }
        }
      }
      else -> {
        reporter.reportOn(source, FirLatticeErrors.ONLY_CLASSES_CAN_BE_INJECTED, context)
        return
      }
    }

    declaration.checkVisibility { source ->
      reporter.reportOn(source, FirLatticeErrors.INJECTED_CLASSES_MUST_BE_VISIBLE, context)
      return
    }

    val constructorToValidate =
      constructorInjections.singleOrNull() ?: declaration.primaryConstructorIfAny(session)
    constructorToValidate?.let {
      it.checkVisibility { source ->
        reporter.reportOn(source, FirLatticeErrors.INJECTED_CONSTRUCTOR_MUST_BE_VISIBLE, context)
        return
      }
    }
  }
}
