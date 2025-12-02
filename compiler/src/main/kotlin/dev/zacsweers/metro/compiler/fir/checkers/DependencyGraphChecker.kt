// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.additionalScopesArgument
import dev.zacsweers.metro.compiler.fir.allAnnotations
import dev.zacsweers.metro.compiler.fir.allScopeClassIds
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.directCallableSymbols
import dev.zacsweers.metro.compiler.fir.findInjectLikeConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isEffectivelyOpen
import dev.zacsweers.metro.compiler.fir.nestedClasses
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.requireContainingClassSymbol
import dev.zacsweers.metro.compiler.fir.resolvedAdditionalScopesClassIds
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import dev.zacsweers.metro.compiler.fir.validateInjectionSiteType
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.metroAnnotations
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
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.dispatchReceiverClassLookupTagOrNull
import org.jetbrains.kotlin.fir.dispatchReceiverClassTypeOrNull
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
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

    // Ensure scope is defined if any additionalScopes are defined
    val scope =
      dependencyGraphAnno.resolvedScopeClassId()?.takeUnless { it == StandardClassIds.Nothing }
    val additionalScopes = dependencyGraphAnno.resolvedAdditionalScopesClassIds().orEmpty()
    if (additionalScopes.isNotEmpty() && scope == null) {
      reporter.reportOn(
        dependencyGraphAnno.additionalScopesArgument()?.source ?: dependencyGraphAnno.source,
        MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
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
          MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
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
          MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
          "Graph class '${declaration.classId.asSingleFqName()}' may not directly extend graph class '${supertypeClass.classId.asSingleFqName()}'. Use @GraphExtension instead.",
        )
        return
      } else {
        scopeAnnotations +=
          supertypeClass.resolvedAnnotationsWithArguments.scopeAnnotations(session)

        if (supertypeClass.isAnnotatedWithAny(session, classIds.graphExtensionFactoryAnnotations)) {
          graphExtensionFactorySupertypes[supertypeRef] = supertypeClass
        }
      }
    }

    // Check supertype extensions
    for ((supertypeRef, graphExtensionFactoryClass) in graphExtensionFactorySupertypes) {
      val graphExtensionClass =
        with(session.compatContext) { graphExtensionFactoryClass.requireContainingClassSymbol() }
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

    val implementedGraphExtensionCreators =
      graphExtensionFactorySupertypes.values.mapToSet { it.classId }

    // Note this doesn't check inherited supertypes. Maybe we should, but where do we report errors?
    for (callable in declaration.symbol.directCallableSymbols()) {
      val annotations =
        callable.metroAnnotations(
          session,
          MetroAnnotations.Kind.OptionalBinding,
          MetroAnnotations.Kind.Provides,
          MetroAnnotations.Kind.Binds,
        )

      val isEffectivelyOpen = with(session.compatContext) { callable.isEffectivelyOpen() }

      if (!isEffectivelyOpen && !annotations.isOptionalBinding) continue

      val isBindsOrProvides = annotations.isBinds || annotations.isProvides
      if (isBindsOrProvides) continue

      // Check graph extensions
      val returnType = callable.resolvedReturnTypeRef.coneType

      // Check if it's a graph extension creator
      val returnTypeClassSymbol = returnType.toClassSymbol(session)
      val isGraphExtensionCreator =
        returnTypeClassSymbol?.isAnnotatedWithAny(
          session,
          classIds.graphExtensionFactoryAnnotations,
        ) == true

      if (isGraphExtensionCreator) {
        val graphExtensionClass =
          with(session.compatContext) { returnTypeClassSymbol.requireContainingClassSymbol() }
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
        // If it's an optionaldep, ensure annotations are propagated
        if (!annotations.isOptionalBinding) {
          for (overridden in callable.directOverriddenSymbolsSafe()) {
            if (
              overridden
                .metroAnnotations(session, MetroAnnotations.Kind.OptionalBinding)
                .isOptionalBinding
            ) {
              reporter.reportOn(
                callable.source,
                MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
                "'${callable.name}' overrides a declaration annotated `@OptionalBinding`, you must propagate these annotations to overrides.",
              )
            }
          }
        }

        val graphExtensionClass =
          callable.directOverriddenSymbolsSafe().firstNotNullOfOrNull { overriddenSymbol ->
            overriddenSymbol.dispatchReceiverClassTypeOrNull()?.toClassSymbol(session)?.takeIf {
              it.isAnnotatedWithAny(session, classIds.graphExtensionFactoryAnnotations)
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

      val isGraphExtension =
        returnTypeClassSymbol?.isAnnotatedWithAny(session, classIds.graphExtensionAnnotations) ==
          true
      if (isGraphExtension) {
        // Check if that extension has a creator. If so, we either must implement that creator or
        // it's an error
        // because they need to use it
        val creator =
          returnTypeClassSymbol.nestedClasses().firstOrNull { nestedClass ->
            nestedClass.isAnnotatedWithAny(session, classIds.graphExtensionFactoryAnnotations)
          }
        if (creator != null) {
          // Final check - make sure this callable belongs to that extension
          val belongsToExtension =
            callable.isOverride &&
              creator.classId !in implementedGraphExtensionCreators &&
              callable.directOverriddenSymbolsSafe().any {
                it.dispatchReceiverClassLookupTagOrNull()?.classId == creator.classId
              }
          if (!belongsToExtension) {
            reporter.reportOn(
              callable.source,
              MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
              "Graph extension '${returnTypeClassSymbol.classId.asSingleFqName()}' has a creator type '${creator.classId.asSingleFqName()}' that must be used to create its instances. Either make '${declaration.classId.asSingleFqName()}' implement '${creator.classId.asSingleFqName()}' or expose an accessor for '${creator.classId.asSingleFqName()}' instead of '${returnTypeClassSymbol.classId.asSingleFqName()}' directly.",
            )
            continue
          }
        } else if (callable.contextParameterSymbols.isNotEmpty()) {
          for (parameter in callable.contextParameterSymbols) {
            reporter.reportOn(
              parameter.source,
              MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
              "Graph extension accessors may not have context parameters.",
            )
          }
          continue
        } else if (callable.receiverParameterSymbol != null) {
          reporter.reportOn(
            callable.receiverParameterSymbol!!.source,
            MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
            "Graph extension accessors may not have extension receivers. Use `@GraphExtension.Factory` instead.",
          )
          continue
        } else if (
          callable is FirNamedFunctionSymbol && callable.valueParameterSymbols.isNotEmpty()
        ) {
          for (parameter in callable.valueParameterSymbols) {
            reporter.reportOn(
              parameter.source,
              MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
              "Graph extension accessors may not have parameters. Use `@GraphExtension.Factory` instead.",
            )
          }
          continue
        }
      }

      // Functions with no params are accessors
      if (
        callable is FirPropertySymbol ||
          (callable is FirNamedFunctionSymbol && callable.valueParameterSymbols.isEmpty())
      ) {
        val hasBody =
          when (callable) {
            is FirPropertySymbol -> callable.getterSymbol?.hasBody == true
            is FirNamedFunctionSymbol -> callable.hasBody
            else -> false
          }

        if (annotations.isOptionalBinding) {
          callable.checkOptionalDepAccessor(isEffectivelyOpen, hasBody)
        } else if (hasBody) {
          continue
        }

        val returnType = callable.resolvedReturnTypeRef.coneType
        if (returnType.isUnit) {
          reporter.reportOn(
            callable.resolvedReturnTypeRef.source ?: callable.source,
            MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members must have a return type and cannot be Unit.",
          )
          continue
        } else if (returnType.isNothing) {
          reporter.reportOn(
            callable.source,
            MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot return Nothing.",
          )
          continue
        }

        validateInjectionSiteType(
          session,
          callable.resolvedReturnTypeRef,
          callable.qualifierAnnotation(session),
          callable.source,
          isAccessor = true,
          isOptionalBinding = annotations.isOptionalBinding,
        )

        val scopeAnnotations = callable.allAnnotations().scopeAnnotations(session)
        for (scopeAnnotation in scopeAnnotations) {
          reporter.reportOn(
            scopeAnnotation.fir.source,
            MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
            "Graph accessor members cannot be scoped.",
          )
        }
        continue
      }

      if (callable is FirNamedFunctionSymbol && callable.valueParameterSymbols.isNotEmpty()) {
        if (!callable.resolvedReturnTypeRef.coneType.isUnit) {
          reporter.reportOn(
            callable.resolvedReturnTypeRef.source,
            MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
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
            val isInjected = classSymbol.findInjectLikeConstructors(session).isNotEmpty()

            if (isInjected) {
              reporter.reportOn(
                parameter.source,
                MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
                "Injected class '${clazz.classId.asSingleFqName()}' is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary.",
              )
            }

            if (annotations.isOptionalBinding) {
              reporter.reportOn(
                callable.source,
                MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
                "Injector functions cannot be annotated with @OptionalBinding.",
              )
            }
            parameter
              .annotationsIn(session, session.classIds.optionalBindingAnnotations)
              .firstOrNull()
              ?.let {
                reporter.reportOn(
                  it.source ?: parameter.source,
                  MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
                  "Injector function parameters cannot be annotated with @OptionalBinding.",
                )
              }
          }
          // > 1
          else -> {
            // TODO Not actually sure what dagger does. Maybe we should support this?
            reporter.reportOn(
              callable.source,
              MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
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
        .annotationsIn(session, classIds.graphExtensionAnnotations)
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
            MetroDiagnostics.GRAPH_CREATORS_ERROR,
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
            MetroDiagnostics.GRAPH_CREATORS_ERROR,
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

  context(reporter: DiagnosticReporter, context: CheckerContext)
  private fun FirCallableSymbol<*>.checkOptionalDepAccessor(
    isEffectivelyOpen: Boolean,
    hasBody: Boolean,
  ) {
    if (!isEffectivelyOpen) {
      reporter.reportOn(
        source,
        MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
        "@OptionalBinding accessors must be open or abstract.",
      )
    }

    // Must have a body
    if (!hasBody) {
      reporter.reportOn(
        source,
        MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
        "@OptionalBinding accessors must have a default body.",
      )
    }
  }
}
