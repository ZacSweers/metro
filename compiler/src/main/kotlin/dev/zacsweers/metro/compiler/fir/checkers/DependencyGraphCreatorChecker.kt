// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isDependencyGraph
import dev.zacsweers.metro.compiler.fir.singleAbstractFunction
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.classKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.ClassId

internal object DependencyGraphCreatorChecker : FirClassChecker(MppCheckerKind.Common) {
  private val PLATFORM_TYPE_PACKAGES =
    setOf("android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "scala.")

  private val NON_INCLUDES_KINDS = setOf(ClassKind.ENUM_CLASS, ClassKind.ANNOTATION_CLASS)

  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val graphFactoryAnnotation =
      declaration.annotationsIn(session, classIds.dependencyGraphFactoryAnnotations).toList()

    if (graphFactoryAnnotation.isEmpty()) return

    declaration.validateApiDeclaration(context, reporter, "DependencyGraph factory") {
      return
    }

    val createFunction =
      declaration.singleAbstractFunction(session, context, reporter, "@DependencyGraph.Factory") {
        return
      }

    createFunction.resolvedReturnType.toClassSymbol(session)?.let {
      if (!it.isDependencyGraph(session)) {
        reporter.reportOn(
          createFunction.resolvedReturnTypeRef.source ?: declaration.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "DependencyGraph.Factory abstract function '${createFunction.name}' must return a dependency graph but found ${it.classId.asSingleFqName()}.",
          context,
        )
        return
      }
    }

    val paramTypes = mutableSetOf<FirTypeKey>()

    for (param in createFunction.valueParameterSymbols) {
      val typeKey = FirTypeKey.from(session, param)
      if (!paramTypes.add(typeKey)) {
        reporter.reportOn(
          param.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "DependencyGraph.Factory abstract function parameters must be unique.",
          context,
        )
        continue
      }

      var isIncludes = false
      var isProvides = false
      var isExtends = false
      for (annotation in param.annotations) {
        if (!annotation.isResolved) continue
        val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: continue
        when (annotationClassId) {
          in classIds.includes -> {
            isIncludes = true
          }

          in classIds.providesAnnotations -> {
            isProvides = true
          }

          in classIds.extends -> {
            isExtends = true
          }
        }
      }

      val reportAnnotationCountError = {
        reporter.reportOn(
          param.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "DependencyGraph.Factory abstract function parameters must be annotated with exactly one @Includes, @Provides, or @Extends.",
          context,
        )
      }
      if ((isIncludes && isProvides) || (isIncludes && isExtends) || (isProvides && isExtends)) {
        reportAnnotationCountError()
        continue
      }

      val type = param.resolvedReturnTypeRef.toClassLikeSymbol(session) ?: continue

      when {
        isIncludes -> {
          if (type.classKind in NON_INCLUDES_KINDS || type.classId.isPlatformType()) {
            reporter.reportOn(
              param.source,
              FirMetroErrors.GRAPH_CREATORS_ERROR,
              "@Includes cannot be applied to enums, annotations, or platform types.",
              context,
            )
          }
        }
        isProvides -> {
          // TODO anything?
        }
        isExtends -> {
          val dependencyGraphAnno =
            type.annotations
              .annotationsIn(session, classIds.dependencyGraphAnnotations)
              .firstOrNull()
          if (dependencyGraphAnno == null) {
            reporter.reportOn(
              param.source,
              FirMetroErrors.GRAPH_CREATORS_ERROR,
              "@Extends types must be annotated with @DependencyGraph.",
              context,
            )
          } else if (
            dependencyGraphAnno.getBooleanArgument("isExtendable".asName(), session) != true
          ) {
            reporter.reportOn(
              param.source,
              FirMetroErrors.GRAPH_CREATORS_ERROR,
              "@Extends graphs must be extendable (set DependencyGraph.isExtendable to true).",
              context,
            )
          }
        }
        else -> {
          reportAnnotationCountError()
        }
      }
    }
  }

  private fun ClassId.isPlatformType(): Boolean {
    return packageFqName.asString().let { packageName ->
      PLATFORM_TYPE_PACKAGES.any { packageName.startsWith(it) }
    }
  }
}
