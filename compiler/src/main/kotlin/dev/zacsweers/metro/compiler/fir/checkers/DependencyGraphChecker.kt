// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.allAnnotations
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.constructors
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isUnit

// TODO
//  - if there's a factory(): Graph in the companion object, error because we'll generate it
//  - if graph is scoped, check that accessors have matching scopes
internal object DependencyGraphChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val dependencyGraphAnno =
      declaration.annotationsIn(session, classIds.graphLikeAnnotations)
        .firstOrNull()

    if (dependencyGraphAnno == null) return

    val graphAnnotationClassId = dependencyGraphAnno.toAnnotationClassIdSafe(session) ?: return
    val isContributed = graphAnnotationClassId in classIds.contributesGraphExtensionAnnotations

    if (isContributed) {
      // Must have a nested class annotated with `@ContributesGraphExtension.Factory`
      val hasNestedFactory =
        declaration.declarations.any { nestedClass ->
          nestedClass is FirClass &&
            nestedClass.isAnnotatedWithAny(
              session,
              classIds.contributesGraphExtensionFactoryAnnotations,
            )
        }
      if (!hasNestedFactory) {
        reporter.reportOn(
          declaration.source,
          FirMetroErrors.GRAPH_CREATORS_ERROR,
          "@${graphAnnotationClassId.relativeClassName.asString()} declarations must have a nested class annotated with @ContributesGraphExtension.Factory.",
          context,
        )
        return
      }
    }

    declaration.validateApiDeclaration(context, reporter, "${graphAnnotationClassId.shortClassName.asString()} declarations") {
      return
    }

    // TODO dagger doesn't appear to error for this case to model off of
    for (constructor in declaration.constructors(session)) {
      if (constructor.valueParameterSymbols.isNotEmpty()) {
        reporter.reportOn(
          constructor.source,
          FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
          "Dependency graphs cannot have constructor parameters. Use @DependencyGraph.Factory instead.",
          context,
        )
        return
      }
    }

    // Note this doesn't check inherited supertypes. Maybe we should, but where do we report errors?
    val callables = declaration.declarations.asSequence().filterIsInstance<FirCallableDeclaration>()

    for (callable in callables) {
      if (!callable.isAbstract) continue

      val isBindsOrProvides =
        callable.isAnnotatedWithAny(
          session,
          classIds.providesAnnotations + classIds.bindsAnnotations,
        )
      if (isBindsOrProvides) continue

      // Functions with no params are accessors
      if (
        callable is FirProperty || (callable is FirFunction && callable.valueParameters.isEmpty())
      ) {
        val returnType = callable.returnTypeRef.coneTypeOrNull
        if (returnType?.isUnit == true) {
          reporter.reportOn(
            callable.returnTypeRef.source ?: callable.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members must have a return type and cannot be Unit.",
            context,
          )
          continue
        } else if (returnType?.isNothing == true) {
          reporter.reportOn(
            callable.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot return Nothing.",
            context,
          )
          continue
        }

        val scopeAnnotations = callable.allAnnotations().scopeAnnotations(session)
        for (scopeAnnotation in scopeAnnotations) {
          reporter.reportOn(
            scopeAnnotation.fir.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot be scoped.",
            context,
          )
        }
        continue
      }

      if (callable is FirFunction && callable.valueParameters.isNotEmpty()) {
        if (callable.returnTypeRef.coneTypeOrNull?.isUnit != true) {
          reporter.reportOn(
            callable.returnTypeRef.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Inject functions must not return anything other than Unit.",
            context,
          )
          continue
        }

        // If it has one param, it's an injector
        when (callable.valueParameters.size) {
          1 -> {
            val parameter = callable.valueParameters[0]
            val clazz = parameter.returnTypeRef.firClassLike(session) ?: continue
            val isInjected = clazz.symbol.findInjectConstructors(session).isNotEmpty()

            if (isInjected) {
              reporter.reportOn(
                parameter.source,
                FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
                "Injected type is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary.",
                context,
              )
            }
          }
          // > 1
          else -> {
            // TODO Not actually sure what dagger does. Maybe we should support this?
            reporter.reportOn(
              callable.source,
              FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
              "Inject functions must have exactly one parameter.",
              context,
            )
          }
        }
      }
    }
  }
}
