// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.allScopeClassIds
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.fir.singleAbstractFunction
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.classKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
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
      declaration.annotationsIn(session, classIds.graphFactoryLikeAnnotations).singleOrNull()
        ?: return

    val annotationClassId = graphFactoryAnnotation.toAnnotationClassId(session) ?: return
    val isContributed = annotationClassId in classIds.contributesGraphExtensionFactoryAnnotations

    if (isContributed) {
      // Must be interfaces
      if (declaration.classKind != ClassKind.INTERFACE) {
        reporter.reportOn(
          declaration.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "${annotationClassId.relativeClassName.asString()} declarations can only be interfaces.",
          context,
        )
        return
      }
    }

    declaration.validateApiDeclaration(
      context,
      reporter,
      "${annotationClassId.relativeClassName.asString()} declarations",
    ) {
      return
    }

    val createFunction =
      declaration.singleAbstractFunction(
        session,
        context,
        reporter,
        "@${annotationClassId.relativeClassName.asString()} declarations",
      ) {
        return
      }

    val targetGraph = createFunction.resolvedReturnType.toClassSymbol(session)
    val targetGraphAnnotation =
      targetGraph
        ?.annotations
        ?.annotationsIn(session, classIds.graphLikeAnnotations)
        ?.singleOrNull()
    targetGraph?.let {
      if (targetGraphAnnotation == null) {
        reporter.reportOn(
          createFunction.resolvedReturnTypeRef.source ?: declaration.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "${annotationClassId.relativeClassName.asString()} abstract function '${createFunction.name}' must return a dependency graph but found ${it.classId.asSingleFqName()}.",
          context,
        )
        return
      }

      if (isContributed) {
        // Target graph must be contributed too
        if (
          targetGraphAnnotation.toAnnotationClassId(session) !in
            classIds.contributesGraphExtensionAnnotations
        ) {
          reporter.reportOn(
            targetGraphAnnotation.source ?: declaration.source,
            FirMetroErrors.GRAPH_CREATORS_ERROR,
            "${annotationClassId.relativeClassName.asString()} abstract function '${createFunction.name}' must return a contributed graph extension but found ${it.classId.asSingleFqName()}.",
            context,
          )
          return
        }
        // Factory must be nested in that class
        if (it.classId != declaration.getContainingClassSymbol()?.classId) {
          reporter.reportOn(
            targetGraphAnnotation.source ?: declaration.source,
            FirMetroErrors.GRAPH_CREATORS_ERROR,
            "${annotationClassId.relativeClassName.asString()} declarations must be nested within the contributed graph they create but was ${declaration.getContainingClassSymbol()?.classId?.asSingleFqName() ?: "top-level"}.",
            context,
          )
          return
        }
      }
    }

    val targetGraphScopes = targetGraphAnnotation?.allScopeClassIds().orEmpty()
    val targetGraphScopeAnnotations =
      targetGraph?.annotations?.scopeAnnotations(session).orEmpty().toSet()

    if (isContributed) {
      val contributedScopes = graphFactoryAnnotation.allScopeClassIds()
      val overlapping = contributedScopes.intersect(targetGraphScopes)
      // Must not contribute to the same scope
      if (overlapping.isNotEmpty()) {
        reporter.reportOn(
          graphFactoryAnnotation.source ?: declaration.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "${annotationClassId.relativeClassName.asString()} declarations must contribute to a different scope than their contributed graph. However, this factory and its contributed graph both contribute to '${overlapping.map { it.asFqNameString() }.single()}'.",
          context,
        )
        return
      }
    }

    val paramTypes = mutableSetOf<FirTypeKey>()

    val seenParentScopesToParent = mutableMapOf<ClassId, ClassId>()
    val seenParentScopeAnnotationsToParent = mutableMapOf<MetroFirAnnotation, ClassId>()
    for (param in createFunction.valueParameterSymbols) {
      val typeKey = FirTypeKey.from(session, param)
      if (!paramTypes.add(typeKey)) {
        reporter.reportOn(
          param.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "${annotationClassId.relativeClassName.asString()} abstract function parameters must be unique.",
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
          "${annotationClassId.relativeClassName.asString()} abstract function parameters must be annotated with exactly one @Includes, @Provides, or @Extends.",
          context,
        )
      }
      if ((isIncludes && isProvides) || (isIncludes && isExtends) || (isProvides && isExtends)) {
        reportAnnotationCountError()
        continue
      }

      val type = param.resolvedReturnTypeRef.toClassLikeSymbol(session) ?: continue

      // Don't allow the target graph as a param
      if (type.classId == targetGraph?.classId) {
        reporter.reportOn(
          param.resolvedReturnTypeRef.source ?: param.source ?: declaration.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "${annotationClassId.relativeClassName.asString()} declarations cannot have their target graph type as parameters.",
          context,
        )
        continue
      }

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
          when {
            dependencyGraphAnno == null -> {
              reporter.reportOn(
                param.source,
                FirMetroErrors.GRAPH_CREATORS_ERROR,
                "@Extends types must be annotated with @DependencyGraph.",
                context,
              )
            }

            dependencyGraphAnno.getBooleanArgument(Symbols.Names.isExtendable, session) != true -> {
              reporter.reportOn(
                param.source,
                FirMetroErrors.GRAPH_CREATORS_ERROR,
                "@Extends graphs must be extendable (set DependencyGraph.isExtendable to true).",
                context,
              )
            }

            targetGraphScopes.isNotEmpty() -> {
              val parentScopes = dependencyGraphAnno.allScopeClassIds()
              val overlaps = parentScopes.intersect(targetGraphScopes)
              if (overlaps.isNotEmpty()) {
                reporter.reportOn(
                  param.source,
                  FirMetroErrors.GRAPH_CREATORS_ERROR,
                  buildString {
                    appendLine(
                      "Graph extensions (@Extends) may not have overlapping aggregation scopes with its parent graph but the following scopes overlap:"
                    )
                    for (overlap in overlaps) {
                      append("- ")
                      appendLine(overlap.asSingleFqName().asString())
                    }
                  },
                  context,
                )
              }
              for (parentScope in parentScopes) {
                val parentClassId = type.classId
                seenParentScopesToParent.put(parentScope, parentClassId)?.let { previous ->
                  reporter.reportOn(
                    param.source,
                    FirMetroErrors.GRAPH_CREATORS_ERROR,
                    buildString {
                      appendLine(
                        "Graph extensions (@Extends) may not have multiple parents with the same aggregation scopes:"
                      )
                      append("Scope: ")
                      appendLine(parentScope.asSingleFqName())
                      append("Parent 1: ")
                      appendLine(previous.asSingleFqName())
                      append("Parent 2: ")
                      appendLine(parentClassId.asSingleFqName())
                    },
                    context,
                  )
                  return
                }
              }
            }

            targetGraphScopeAnnotations.isNotEmpty() -> {
              val parentScopeAnnotations = type.annotations.scopeAnnotations(session).toSet()
              val overlaps = parentScopeAnnotations.intersect(targetGraphScopeAnnotations)
              if (overlaps.isNotEmpty()) {
                reporter.reportOn(
                  param.source,
                  FirMetroErrors.GRAPH_CREATORS_ERROR,
                  buildString {
                    appendLine(
                      "Graph extensions (@Extends) may not have overlapping scope annotations with its parent graph but the following annotations overlap:"
                    )
                    for (overlap in overlaps) {
                      append("- ")
                      appendLine(overlap.simpleString())
                    }
                  },
                  context,
                )
                return
              }

              for (parentScope in parentScopeAnnotations) {
                val parentClassId = type.classId
                seenParentScopeAnnotationsToParent.put(parentScope, parentClassId)?.let { previous
                  ->
                  reporter.reportOn(
                    param.source,
                    FirMetroErrors.GRAPH_CREATORS_ERROR,
                    buildString {
                      appendLine(
                        "Graph extensions (@Extends) may not have multiple parents with the same aggregation scopes:"
                      )
                      append("Scope: ")
                      appendLine(parentScope.simpleString())
                      append("Parent 1: ")
                      appendLine(previous.asSingleFqName())
                      append("Parent 2: ")
                      appendLine(parentClassId.asSingleFqName())
                    },
                    context,
                  )
                }
              }
            }
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
