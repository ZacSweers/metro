// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.additionalScopesArgument
import dev.zacsweers.metro.compiler.fir.allAnnotations
import dev.zacsweers.metro.compiler.fir.allScopeClassIds
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.directCallableSymbols
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.nestedClasses
import dev.zacsweers.metro.compiler.fir.requireContainingClassSymbol
import dev.zacsweers.metro.compiler.fir.resolvedAdditionalScopesClassIds
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import kotlin.collections.orEmpty
import kotlin.sequences.orEmpty
import kotlin.sequences.toSet
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.constructors
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.dispatchReceiverClassTypeOrNull
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

// TODO
//  - if there's a factory(): Graph in the companion object, error because we'll generate it
//  - if graph is scoped, check that accessors have matching scopes
internal object DependencyGraphChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    val dependencyGraphAnno =
      declaration.annotationsIn(session, classIds.graphLikeAnnotations).firstOrNull() ?: return

    val graphAnnotationClassId = dependencyGraphAnno.toAnnotationClassIdSafe(session) ?: return
    val isContributed = graphAnnotationClassId in classIds.contributesGraphExtensionAnnotations

    if (isContributed) {
      // Must have a nested class annotated with `@ContributesGraphExtension.Factory`
      val hasNestedFactory =
        declaration.symbol.nestedClasses().any { nestedClass ->
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
        )
        return
      }
    }

    // Ensure scope is defined if any additionalScopes are defined
    val scope =
      dependencyGraphAnno.resolvedScopeClassId()?.takeUnless { it == StandardClassIds.Nothing }
    val additionalScopes = dependencyGraphAnno.resolvedAdditionalScopesClassIds().orEmpty()
    if (additionalScopes.isNotEmpty() && scope == null) {
      reporter.reportOn(
        dependencyGraphAnno.additionalScopesArgument()?.source ?: dependencyGraphAnno.source,
        FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
        "@${graphAnnotationClassId.shortClassName.asString()} should have a primary `scope` defined if `additionalScopes` are defined.",
      )
    }

    declaration.validateApiDeclaration(
      "${graphAnnotationClassId.shortClassName.asString()} declarations",
      checkConstructor = true,
    ) {
      return
    }

    // TODO dagger doesn't appear to error for this case to model off of
    for (constructor in declaration.constructors(session)) {
      if (constructor.valueParameterSymbols.isNotEmpty()) {
        reporter.reportOn(
          constructor.source,
          FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
          "Dependency graphs cannot have constructor parameters. Use @DependencyGraph.Factory instead.",
        )
        return
      }
    }

    val aggregationScopes = dependencyGraphAnno.allScopeClassIds()
    val scopeAnnotations = mutableSetOf<MetroFirAnnotation>()
    scopeAnnotations += declaration.annotations.scopeAnnotations(session)

    val graphExtensionFactorySupertypes = mutableMapOf<FirTypeRef, FirClassLikeSymbol<*>>()

    for (supertypeRef in declaration.superTypeRefs) {
      val supertype = supertypeRef.coneType as? ConeClassLikeType ?: continue
      val supertypeClass = supertype.lookupTag.toSymbol(session) ?: continue
      if (supertypeClass.isAnnotatedWithAny(session, classIds.graphLikeAnnotations)) {
        reporter.reportOn(
          supertypeRef.source ?: declaration.source,
          FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
          "Graph class '${declaration.classId.asSingleFqName()}' may not directly extend graph class '${supertypeClass.classId.asSingleFqName()}'. Use @GraphExtension instead.",
        )
        return
      } else {
        scopeAnnotations +=
          supertypeClass.resolvedAnnotationsWithArguments.scopeAnnotations(session)

        if (
          supertypeClass.isAnnotatedWithAny(session, classIds.allGraphExtensionFactoryAnnotations)
        ) {
          graphExtensionFactorySupertypes[supertypeRef] = supertypeClass
        }
      }
    }

    // Check supertype extensions
    for ((supertypeRef, graphExtensionFactoryClass) in graphExtensionFactorySupertypes) {
      val graphExtensionClass = graphExtensionFactoryClass.requireContainingClassSymbol()
      validateGraphExtension(
        session = session,
        classIds = classIds,
        parentGraph = declaration,
        graphExtension = graphExtensionClass,
        source = supertypeRef.source,
        parentScopeAnnotations = scopeAnnotations,
        parentAggregationScopes = aggregationScopes,
      )
    }

    // Note this doesn't check inherited supertypes. Maybe we should, but where do we report errors?
    for (callable in declaration.symbol.directCallableSymbols()) {
      if (!callable.isAbstract) continue

      val isBindsOrProvides =
        callable.isAnnotatedWithAny(
          session,
          classIds.providesAnnotations + classIds.bindsAnnotations,
        )
      if (isBindsOrProvides) continue

      // Check graph extensions
      val returnType = callable.resolvedReturnTypeRef.coneType

      // Check if it's a graph extension creator
      val returnTypeClassSymbol = returnType.toClassSymbol(session)
      val isGraphExtensionCreator =
        returnTypeClassSymbol?.isAnnotatedWithAny(
          session,
          classIds.allGraphExtensionFactoryAnnotations,
        ) == true

      if (isGraphExtensionCreator) {
        val graphExtensionClass = returnTypeClassSymbol.requireContainingClassSymbol()
        validateGraphExtension(
          session = session,
          classIds = classIds,
          parentGraph = declaration,
          graphExtension = graphExtensionClass,
          source = callable.source,
          parentScopeAnnotations = scopeAnnotations,
          parentAggregationScopes = aggregationScopes,
        )
        continue
      }

      if (callable.isOverride) {
        val graphExtensionClass =
          callable.directOverriddenSymbolsSafe(context).firstNotNullOfOrNull { overriddenSymbol ->
            overriddenSymbol.dispatchReceiverClassTypeOrNull()?.toClassSymbol(session)?.takeIf {
              it.isAnnotatedWithAny(session, classIds.allGraphExtensionFactoryAnnotations)
            }
          }
        if (graphExtensionClass != null) {
          validateGraphExtension(
            session = session,
            classIds = classIds,
            parentGraph = declaration,
            graphExtension = graphExtensionClass,
            source = callable.source,
            parentScopeAnnotations = scopeAnnotations,
            parentAggregationScopes = aggregationScopes,
          )
          continue
        }
      }

      // Functions with no params are accessors
      if (
        callable is FirPropertySymbol ||
          (callable is FirNamedFunctionSymbol && callable.valueParameterSymbols.isEmpty())
      ) {
        val returnType = callable.resolvedReturnTypeRef.coneType
        if (returnType.isUnit) {
          reporter.reportOn(
            callable.resolvedReturnTypeRef.source ?: callable.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members must have a return type and cannot be Unit.",
          )
          continue
        } else if (returnType.isNothing) {
          reporter.reportOn(
            callable.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot return Nothing.",
          )
          continue
        }

        val scopeAnnotations = callable.allAnnotations().scopeAnnotations(session)
        for (scopeAnnotation in scopeAnnotations) {
          reporter.reportOn(
            scopeAnnotation.fir.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot be scoped.",
          )
        }
        continue
      }

      if (callable is FirNamedFunctionSymbol && callable.valueParameterSymbols.isNotEmpty()) {
        if (!callable.resolvedReturnTypeRef.coneType.isUnit) {
          reporter.reportOn(
            callable.resolvedReturnTypeRef.source,
            FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
            "Inject functions must not return anything other than Unit.",
          )
          continue
        }

        // If it has one param, it's an injector
        when (callable.valueParameterSymbols.size) {
          1 -> {
            val parameter = callable.valueParameterSymbols[0]
            val clazz = parameter.resolvedReturnTypeRef.firClassLike(session) ?: continue
            val classSymbol = clazz.symbol as? FirClassSymbol<*> ?: continue
            val isInjected = classSymbol.findInjectConstructors(session).isNotEmpty()

            if (isInjected) {
              reporter.reportOn(
                parameter.source,
                FirMetroErrors.DEPENDENCY_GRAPH_ERROR,
                "Injected type is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary.",
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
            )
          }
        }
      }
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun validateGraphExtension(
    session: FirSession,
    classIds: ClassIds,
    parentGraph: FirClass,
    source: KtSourceElement?,
    graphExtension: FirClassLikeSymbol<*>,
    parentAggregationScopes: Set<ClassId>,
    parentScopeAnnotations: Set<MetroFirAnnotation>,
  ) {
    val dependencyGraphAnno =
      graphExtension.resolvedCompilerAnnotationsWithClassIds
        .annotationsIn(session, classIds.allGraphExtensionAnnotations)
        .firstOrNull()

    val targetGraphScopes = dependencyGraphAnno?.allScopeClassIds().orEmpty()
    val targetGraphScopeAnnotations =
      graphExtension.resolvedCompilerAnnotationsWithClassIds.scopeAnnotations(session).toSet()

    when {
      targetGraphScopes.isNotEmpty() -> {
        val overlaps = parentAggregationScopes.intersect(targetGraphScopes)
        if (overlaps.isNotEmpty()) {
          reporter.reportOn(
            source ?: parentGraph.source,
            FirMetroErrors.GRAPH_CREATORS_ERROR,
            buildString {
              appendLine(
                "Graph extension '${graphExtension.classId.asSingleFqName()}' has overlapping aggregation scopes with parent graph '${parentGraph.classId.asSingleFqName()}':"
              )
              for (overlap in overlaps) {
                append("- ")
                appendLine(overlap.asSingleFqName().asString())
              }
            },
          )
        }
      }

      targetGraphScopeAnnotations.isNotEmpty() -> {
        val overlaps = parentScopeAnnotations.intersect(targetGraphScopeAnnotations)
        if (overlaps.isNotEmpty()) {
          reporter.reportOn(
            source ?: parentGraph.source,
            FirMetroErrors.GRAPH_CREATORS_ERROR,
            buildString {
              appendLine(
                "Graph extension '${graphExtension.classId.asSingleFqName()}' has overlapping scope annotations with parent graph '${parentGraph.classId.asSingleFqName()}':"
              )
              for (overlap in overlaps) {
                append("- ")
                appendLine(overlap.simpleString())
              }
            },
          )
          return
        }
      }
    }
  }
}
